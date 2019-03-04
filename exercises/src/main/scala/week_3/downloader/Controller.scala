package week_3.downloader

import akka.actor.{Actor, ActorLogging, ActorRef, Props, ReceiveTimeout}
import week_2.downloader.Controller.{CheckUrl, Result}

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
  var children = Set.empty[ActorRef]

  context.setReceiveTimeout(10.seconds)

  override def receive: Receive = {
    case CheckUrl(url, depth) => {
      //log.debug("Checking {}", url)
      if (!cache(url) && depth > 0) {
        val nextGetter = context.actorOf(Props(new Getter(url, depth - 1)))
        children += nextGetter
      }
      cache += url
    }

    case Getter.Done => {
      val sender = context.sender
      children -= sender
      if (children.isEmpty) {
        context.parent ! Result(cache)
      }
    }

    case ReceiveTimeout =>
      children foreach {
        _ ! Getter.Abort
      }

  }
}
