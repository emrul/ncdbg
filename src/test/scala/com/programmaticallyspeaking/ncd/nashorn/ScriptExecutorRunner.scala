package com.programmaticallyspeaking.ncd.nashorn

import java.io.{OutputStreamWriter, PrintWriter}
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeoutException

import akka.actor.{Actor, ActorRef}
import ch.qos.logback.classic.Level
import com.programmaticallyspeaking.ncd.host.ScriptEvent
import com.programmaticallyspeaking.ncd.infra.{CancellableFuture, DelayedFuture}
import com.programmaticallyspeaking.ncd.messaging.{Observer, SerializedSubject, Subscription}
import com.programmaticallyspeaking.ncd.testing.{MemoryAppender, StringUtils}
import com.sun.jdi.{Bootstrap, VirtualMachine}
import com.sun.jdi.connect.LaunchingConnector
import com.sun.jdi.event.VMStartEvent
import org.slf4s.Logging

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

object ScriptExecutorRunner {
  case class Start(javaHome: Option[String], timeout: FiniteDuration)
  case class Started(host: NashornScriptHost)
  case class StartError(progress: String, error: Option[Throwable])

  case class ExecuteScript(script: String, observer: Observer[ScriptEvent], timeout: FiniteDuration)
  case object ScriptExecutionDone
  case class ScriptFailure(reason: String)
  case object ScriptWillExecute

  case class ObserveScriptEvents(observer: Observer[ScriptEvent])
  case class ObserveScriptEventsResponse(subscription: Subscription)

  case object GetProgress
  case class ProgressResponse(progress: String)

  case object Stop

  private case class StdOut(text: String)
  private case class StdErr(text: String)
  private case class HostFailure(t: Throwable)
  private case object HostInitComplete
  private case object VmIsReady
  private case class ReportProgress(p: String)
}

class ScriptExecutorRunner(scriptExecutor: ScriptExecutorBase)(implicit executionContext: ExecutionContext) extends Actor with Logging {
  import scala.collection.JavaConverters._
  import ScriptExecutorRunner._

  private var vmStdinWriter: PrintWriter = _
  private var hostEventSubscription: Subscription = _
  private var host: NashornScriptHost = _

  private val vmReadyPromise = Promise[Unit]()

  // Tracks progress for better timeout failure reporting
  private val progress = ListBuffer[String]()
  private def nowString = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT)
  private def reportProgress(msg: String): Unit = progress += s"[$nowString] $msg"
  private def summarizeProgress() = progress.mkString("\n")
  private def clearProgress() = progress.clear()

  private var startSender: ActorRef = _
  private var scriptSender: ActorRef = _
  private var scriptTimeoutFuture: CancellableFuture[Unit] = _
  private var startTimeoutFuture: CancellableFuture[Unit] = _
  private var startTimeout: FiniteDuration = _

  private var logSubscription: Subscription = _

  private val eventSubject = new SerializedSubject[ScriptEvent]
  private val subscriptions = mutable.Queue[Subscription]()

  private def unsubscribeAll(): Unit = while (subscriptions.nonEmpty) subscriptions.dequeue().unsubscribe()

  private def addObserver(observer: Observer[ScriptEvent]): Unit = {
    unsubscribeAll()
    Option(observer).foreach(o => subscriptions.enqueue(eventSubject.subscribe(o)))
  }

  def common: Receive = {
    case Stop =>
      unsubscribeAll()
      Option(logSubscription).foreach(_.unsubscribe())
      Option(vmStdinWriter).foreach(_.close())
      Option(host).foreach(_.virtualMachine.process().destroy())
      context.stop(self)

    case GetProgress =>
      sender ! ProgressResponse(summarizeProgress())

    case ReportProgress(p) =>
      reportProgress(p)

    case ObserveScriptEvents(obs) =>
      val sub = eventSubject.subscribe(obs)
      sender ! ObserveScriptEventsResponse(sub)
  }

  private def bumpStartTimeout: Unit = Option(startSender) match {
    case Some(_) =>
      Option(startTimeoutFuture).foreach(_.cancel())
      startTimeoutFuture = DelayedFuture(startTimeout) {
        Option(startSender).foreach { s =>
          s ! StartError(summarizeProgress(), Some(new TimeoutException("Timed out waiting for VM to start")))
          self ! Stop
        }
      }
    case None => // noop
  }

  private def cancelStartTimeout: Unit = {
    Option(startTimeoutFuture).foreach(_.cancel())
    startTimeoutFuture = null
    startTimeout = null
  }

  override def receive: Receive = common orElse {
    case Start(javaHome, timeout) =>
      Try(launchVm(javaHome)) match {
        case Success(vm) =>
          // Save for later use
          startSender = sender

          vmStdinWriter = new PrintWriter(new OutputStreamWriter(vm.process().getOutputStream()), true)
          val debugger = new NashornDebugger()
          host = debugger.create(vm)(context.system)

          context.become(vmStarted)
          captureLogs()
          setupHost(host)

          startTimeout = timeout
          bumpStartTimeout

        case Failure(NonFatal(t)) =>
          sender ! StartError("", Some(t))
          self ! Stop
        case Failure(t) => throw t // fatal
      }
  }

  def vmStarted: Receive = common orElse {
    case StdOut(output) =>
      bumpStartTimeout // Life sign
      if (output == Signals.ready) {
        // When we receive "ready", the VM is ready to listen for "go".
        reportProgress("Got the ready signal from the VM")
        vmReadyPromise.success(())
      } else {
        reportProgress("VM output: " + output)
      }
    case StdErr(error) =>
      reportProgress("VM error: " + error)
    case HostInitComplete =>
      bumpStartTimeout // Life sign

      // Host initialization is complete, so let ScriptExecutor know that it can continue.
      reportProgress("host initialization complete")

      // Wait until we observe that the VM is ready to receive the go command.
      vmReadyPromise.future.onComplete {
        case Success(_) =>
          reportProgress("VM is ready to go!")
          self ! VmIsReady

        case Failure(t) => startSender ! StartError(summarizeProgress(), Some(t))
      }
    case VmIsReady =>
      context.become(upAndRunning)
      sendToVm(Signals.go)
      startSender ! Started(host)
      startSender = null
      cancelStartTimeout
  }

  def upAndRunning: Receive = common orElse {
    case StdOut(output) =>
      if (output == Signals.scriptDone) {
        scriptSender ! ScriptExecutionDone
        scriptSender = null
        scriptTimeoutFuture.cancel()
        scriptTimeoutFuture = null
      } else {
        reportProgress("VM output: " + output)
      }
    case StdErr(error) =>
      reportProgress("VM error: " + error)

    case ExecuteScript(script, observer, timeout) if scriptSender == null =>
      clearProgress()
      reportProgress("Sending script to VM!")
      scriptSender = sender
      sender ! ScriptWillExecute
      scriptTimeoutFuture = DelayedFuture(timeout) {
        Option(scriptSender).foreach { s =>
          s ! ScriptFailure("Timed out waiting for the script: Progress:\n" + summarizeProgress())
          self ! Stop
        }
      }

      addObserver(observer)
      sendToVm(script, encodeBase64 = true)

    case ExecuteScript(_, _, _) =>
      sender ! ScriptFailure("outstanding script not done")
  }

  private def setupHost(host: NashornScriptHost): Unit = {
    reportProgress("VM is running, setting up host")
    hostEventSubscription = host.events.subscribe(new Observer[ScriptEvent] {
      override def onError(error: Throwable): Unit = self ! HostFailure(error)

      override def onComplete(): Unit = self ! HostFailure(new Exception("complete"))

      override def onNext(item: ScriptEvent): Unit = item match {
        case InitialInitializationComplete => self ! HostInitComplete
        case other =>
          log.debug("Dispatching to event observers: " + other)
          eventSubject.onNext(other)
      }
    })
    host.pauseOnBreakpoints()
  }

  private def sendToVm(data: String, encodeBase64: Boolean = false): Unit = {
    val dataToSend = if (encodeBase64) StringUtils.toBase64(data) else data
    log.info("Sending to VM: " + dataToSend)
    vmStdinWriter.println(dataToSend)
  }

  private def launchVm(javaHome: Option[String]): VirtualMachine = {
    val conn = findLaunchingConnector()
    val args = conn.defaultArguments()
    val homeArg = args.get("home")
    val currentHome = homeArg.value()

    val classPath = System.getProperty("java.class.path")
    val cp = javaHome match {
      case Some(jh) =>
        val pathSeparator = System.getProperty("path.separator")
        classPath.split(pathSeparator).map { part =>
          if (part.startsWith(currentHome)) part.replace(currentHome, jh)
          else part
        }.mkString(pathSeparator)
      case None => classPath
    }
    javaHome.foreach(homeArg.setValue)

    val className = scriptExecutor.getClass.getName.replaceAll("\\$$", "")
    val mainArg = args.get("main")
    mainArg.setValue(s"""-Dnashorn.args=--language=es6 -cp "$cp" $className""")

    val vm = conn.launch(args)

    def logVirtualMachineOutput(s: String) = self ! StdOut(s)
    def logVirtualMachineError(s: String) = self ! StdErr(s)

    new StreamReadingThread(vm.process().getInputStream(), logVirtualMachineOutput).start()
    new StreamReadingThread(vm.process().getErrorStream(), logVirtualMachineError).start()

    waitUntilStarted(vm)
    vm
  }

  private def waitUntilStarted(vm: VirtualMachine): Unit = {
    var attempts = 5
    var done = false
    while (!done && attempts >= 0) {
      val eventSet = vm.eventQueue().remove(500L)
      Option(eventSet).foreach { es =>
        es.asScala.foreach {
          case _: VMStartEvent =>
            done = true
          case _ =>
        }
        es.resume()
      }
      attempts -= 1
    }
    if (!done) throw new Exception("VM didn't start")
  }

  private def captureLogs(): Unit = {
    logSubscription = MemoryAppender.logEvents.subscribe(Observer.from {
      case event if event.getLevel.isGreaterOrEqual(Level.DEBUG) => // if event.getLoggerName == getClass.getName =>
        val simpleLoggerName = event.getLoggerName.split('.').last
        var txt = s"[$simpleLoggerName][${event.getLevel}]: ${event.getMessage}"
        Option(event.getThrowableProxy).foreach { proxy =>
          txt += "\n" + proxy.getMessage
          proxy.getStackTraceElementProxyArray.foreach { st =>
            txt += "\n  " + st.toString
          }
        }
        self ! ReportProgress(txt)
    })
  }

  private def findLaunchingConnector(): LaunchingConnector = Bootstrap.virtualMachineManager().defaultConnector()
}
