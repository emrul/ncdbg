package com.programmaticallyspeaking.ncd.nashorn

import com.programmaticallyspeaking.ncd.host.ExceptionPauseType
import com.programmaticallyspeaking.ncd.nashorn.NashornDebuggerHost.isInfrastructureThread
import com.sun.jdi.event.Event
import com.sun.jdi.request.EventRequest
import com.sun.jdi.{ClassType, Location, Method, StackFrame}
import org.slf4s.Logging

import scala.language.reflectiveCalls
import scala.util.Try

object PauseSupport {
  type CanAddClassFilter = {def addClassFilter(s: String): Unit}
}

trait PauseSupport { self: NashornDebuggerHost with Logging =>
  import NashornDebuggerHost._
  import scala.collection.JavaConverters._
  import PauseSupport._

  protected def enableExceptionPausing(ct: ClassType): Unit = {
    log.info(s"Enabling breaking on exceptions in script classes.")
    val erm = virtualMachine.eventRequestManager()
    // Note that we want to pause on both caught and uncaught exceptions at all times, because we have
    // a custom definition of what "uncaught" means, since Nashorn may create a Java adapter that catches and
    // rethrows an exception.
    // We don't necessarily find ECMAException at VM startup, so we don't have it available here.
    val request = erm.createExceptionRequest(null, true, true)
    request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD) // TODO: Duplicate code
    // We're only interested in exceptions thrown in scripts
    request.addClassFilter(ScriptClassNamePrefix + "*")
    request.setEnabled(true)
  }

  override def pauseOnExceptions(pauseType: ExceptionPauseType): Unit = {
    if (pauseType != currentExceptionPauseType) {
      currentExceptionPauseType = pauseType

      val pauseOnCaught = pauseType == ExceptionPauseType.Caught || pauseType == ExceptionPauseType.All
      val pauseOnUncaught = pauseType == ExceptionPauseType.Uncaught || pauseType == ExceptionPauseType.All

      if (pauseOnCaught || pauseOnUncaught) {
        log.info(s"Will pause on exceptions (caught=$pauseOnCaught, uncaught=$pauseOnUncaught)")
      } else {
        log.info("Won't pause on exceptions")
      }
    } else log.debug(s"Ignoring request to set same pause type $pauseType")
  }

  override def resume(): Unit = {
    resumeWhenPaused()
  }

  // allLineLocations may throw AbsentInformationException
  private def lineLocations(m: Method): Seq[Location] = Try(m.allLineLocations().asScala).getOrElse(Seq.empty)

  private def locationsForStackFrame(sf: StackFrame): Seq[Location] = {
    // Use all locations since the current line/location may be inside a loop, i.e. execution can continue on a line
    // before the current one. A possible optimization is to check if there are branch instructions in the byte codes
    // and use subsequent or all locations depending on that, but it's more complex and I'm not sure it's worth the
    // trouble.
    lineLocations(sf.location().method())
  }

  private def setOneOffBreakpoint(location: Location) = {
    val erm = virtualMachine.eventRequestManager()
    val bp = erm.createBreakpointRequest(location)
    bp.addCountFilter(1)
    bp.setEnabled(true)
    bp
  }

  private def configureRequest(request: EventRequest with CanAddClassFilter) = {
    // TODO: Lots of duplicate code here wrt how we create breakpoints.
    request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
    // TODO: Should StepRequestClassFilter be this string?? But then maybe it won't be possible to step over _to_
    // TODO: a debugger statement. Check if we have a test for that!
    request.addClassFilter(ScriptClassNamePrefix + "*")
    request.addCountFilter(1)
    request.setEnabled(true)
    request
  }

  private def setMethodEntryBreakpoint() = {
    val request = virtualMachine.eventRequestManager().createMethodEntryRequest()
    configureRequest(request)
  }

  private def setMethodExitBreakpoint() = {
    val request = virtualMachine.eventRequestManager().createMethodExitRequest()
    configureRequest(request)
  }

  override def pauseAtNextStatement(): Unit = pausedData match {
    case Some(_) =>
      log.warn("Won't pause at next statement because we're already paused!")
    case None =>

      // Suspend the VM, otherwise we cannot get thread frames safely.
      virtualMachine.suspend()
      try {
        // Two approaches to pausing:
        //
        // 1. Check if we're executing inside a script function. Find threads where there's a script involved, then
        //    enable one-time breakpoints on all subsequent locations in the executing script method.
        //
        // 2. Set a method-entry breakpoint for script functions.
        //
        // We do both of these to ensure that the script is paused.

        // We're interested in all non-infrastructure threads.
        val relevantThreads = virtualMachine.allThreads().asScala.filterNot(isInfrastructureThread)

        // Get possible locations to break on. For each thread, find the first stack frame with non-empty locations
        // and get them (the locations).
        val locationsToSetBreakpointsOn = relevantThreads.flatMap { thread =>
          // Create a view so that we can map and find lazily
          val viewOfFrames = thread.frames().asScala.view
          viewOfFrames.map(locationsForStackFrame).find(_.nonEmpty).getOrElse(Seq.empty)
        }

        log.info("Will pause at next script statement")
        log.debug(s"Creating one-off breakpoint request(s) at [${locationsToSetBreakpointsOn.mkString(", ")}] and method entry/exit requests for pausing.")

        // Create both a method-entry request and breakpoint requests.
        val methodEntryRequest = setMethodEntryBreakpoint()
        val methodExitRequest = setMethodExitBreakpoint()
        val breakpoints = locationsToSetBreakpointsOn.map(setOneOffBreakpoint)
        val allRequests = breakpoints ++ Seq(methodEntryRequest, methodExitRequest)

        // When we observe an event for a request, clear all the requests (of both types).
        def eventHandler(ev: Event) = {
          val erm = virtualMachine.eventRequestManager()
          erm.deleteEventRequests(allRequests.asJava)
          false // don't consume the event
        }

        // Associate the handler with each request
        allRequests.foreach(beforeEventIsHandled(_)(eventHandler))
      } finally virtualMachine.resume()
  }

  override def setSkipAllPauses(skip: Boolean): Unit = {
    log.info(if (skip) "Skipping all pauses" else "Not skipping all pauses")
    disablePausingAltogether = skip
  }

}
