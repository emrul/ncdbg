package com.programmaticallyspeaking.ncd.boot

import java.net.ConnectException

import akka.actor.ActorSystem
import com.programmaticallyspeaking.ncd.chrome.domains.DefaultDomainFactory
import com.programmaticallyspeaking.ncd.chrome.net.{FilePublisher, FileServer, WebSocketServer}
import com.programmaticallyspeaking.ncd.host.{ScriptEvent, ScriptHost}
import com.programmaticallyspeaking.ncd.ioc.Container
import com.programmaticallyspeaking.ncd.messaging.Observer
import com.programmaticallyspeaking.ncd.nashorn.{NashornDebugger, NashornDebuggerConnector, NashornScriptHost}
import org.slf4s.Logging

import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object Boot extends App with Logging {
  val conf = new Conf(args)

  implicit val system = ActorSystem()
  import system.dispatcher

  val connectAddr = conf.connect()
  val connector = new NashornDebuggerConnector(connectAddr.host, connectAddr.port)
  val debuggerReady = connector.connect().map(vm => new NashornDebugger().create(vm))

  debuggerReady.andThen {
    case Success(host) =>
      startListening(host)

      val listenAddr = conf.listen()
      val fileServer = new FileServer(listenAddr.host, listenAddr.port)
      val container = new BootContainer(fileServer.publisher, host)

      startHttpServer(container, fileServer)
    case Failure(t) =>
      t match {
        case _: ConnectException =>
          log.error("Failed to connect to the debug target.")
          log.error("Please make sure that the debug target is started with debug VM arguments, for example:")
          log.error("  -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=7777")
        case _ =>
          log.error("Failed to start the debugger", t)
      }
      system.terminate()
      die(1)
  }

  private def die(code: Int): Nothing = {
    system.terminate()
    System.exit(code)
    ???
  }

  private def startListening(host: NashornScriptHost) = {
    host.events.subscribe(new Observer[ScriptEvent] {
      override def onNext(item: ScriptEvent): Unit = {}

      override def onError(error: Throwable): Unit = {
        log.error("Exiting due to an error", error)
        die(2)
      }

      override def onComplete(): Unit = {
        log.info("Exiting because the debug target disconnected")
        die(0)
      }
    })
  }

  private def startHttpServer(container: Container, fileServer: FileServer): Unit = {
    val server = new WebSocketServer(new DefaultDomainFactory(container), Some(fileServer))
    try {
      val listenAddr = conf.listen()
      server.start(listenAddr.host, listenAddr.port)
      log.info(s"Server is listening on ${listenAddr.host}:${listenAddr.port}")
      val url = s"chrome-devtools://devtools/bundled/inspector.html?experiments=true&v8only=true&ws=${listenAddr.host}:${listenAddr.port}/dbg"
      log.info("Open this URL in Chrome: " + url)
    } catch {
      case NonFatal(t) =>
        log.error("Binding failed", t)
        die(2)
    }
  }

  class BootContainer(filePublisher: FilePublisher, scriptHost: ScriptHost) extends Container(Seq(filePublisher, scriptHost))
}
