package week_3.downloader

import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, ReceiveTimeout, SupervisorStrategy, Terminated}
import week_3.downloader.Controller.{CheckUrl, Result}

import scala.concurrent.duration._


object Controller {

  case class CheckUrl(url: String, depth: Int)

  case class Result(links: Set[String])

}

class Controller extends Actor with ActorLogging {
  /*
   Why `var`?
   Since `cache` is shared, we prefer an immutable data structure. Had we
   used a `val`, the set would have been mutable.
  */
  var cache = Set.empty[String]

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 5) {
    case _: Exception =>
      SupervisorStrategy.Restart
  }

  context.setReceiveTimeout(10.seconds)

  override def receive: Receive = {
    case CheckUrl(url, depth) =>
      if (!cache(url) && depth > 0) {
        val nextName = url.replace("[http|s]","").replaceAll("[.|/|:|#]", "_")
        val nextGetter = context.actorOf(Props(
          new Getter(url, depth - 1))
          //s"GET_${nextName}_at_${depth}"
        )
        context.watch(nextGetter)
      }
      cache += url

    case Terminated(_) =>
      if (context.children.isEmpty) {
        log.debug("DONEEEE...")
        context.parent ! Result(cache)
      }else
        log.debug("CTRL continued...")

    case ReceiveTimeout =>
      context.children foreach context.stop
  }
}
