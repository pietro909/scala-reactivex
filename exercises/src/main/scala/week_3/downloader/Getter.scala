package week_3.downloader

import akka.actor.{Actor, ActorLogging, Status}
import akka.pattern.pipe

object Getter {
  // Done is superseded by the Terminated message by Akka
  // Abort is replaced by the context.stop command
}

class Getter(url: String, depth: Int) extends Actor with ActorLogging {
  implicit val exec = context.dispatcher

  val future = AsyncWebClient.get(url)
  future.pipeTo(self)
  /*
    `future.pipeTo(self)` is syntactic sugar for:

    ```
    future onComplete {
      case Success(body) =>
        self ! body
      case Failure(exception) =>
        self ! Status.Failure(exception)
    }
    ```

    Even cooler: `AsyncWebClient get url pipeTo self` :-)
  */

  override def receive: Receive = {
    case body: String =>
      for (link <- findLinks(body, url))
        context.parent ! Controller.CheckUrl(link, depth)
      log.debug("all links sent, stopping")
      context.stop(self)

    case _: Status.Failure =>
      log.error("Http failed")
      context.stop(self)
  }
}
