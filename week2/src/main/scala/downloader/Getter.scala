package downloader

import akka.actor.{Actor, ActorLogging, Status}
import downloader.Getter.{Abort, Done}
import akka.pattern.pipe

import scala.util.{Failure, Success}

object Getter {

  case object Done

  case object Abort

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
      stop
    case Abort =>
      log.warning("Abort received")
      stop
    case _: Status.Failure =>
      log.error("Http failed")
      stop

  }

  def stop() = {
    context.parent ! Done
    context.stop(self)
  }
}
