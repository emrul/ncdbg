package com.programmaticallyspeaking.ncd.chrome.net

object Protocol {
  sealed trait Message

  sealed trait IdentifiableMessage extends Message{
    val id: String
  }

  case class IncomingMessage(id: String, method: String, params: Map[String, Any]) extends IdentifiableMessage {
    def domain() = method.split('.').toList match {
      case d :: m :: Nil => d
      case _ => throw new IllegalArgumentException("Not a well-formed method: " + method)
    }
  }
  case class ErrorResponse(id: String, error: String) extends IdentifiableMessage
  case class EmptyResponse(id: String) extends IdentifiableMessage
  case class Response(id: String, result: Any) extends IdentifiableMessage

  case class Event(method: String, params: Any) extends Message
}
