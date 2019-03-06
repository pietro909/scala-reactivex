package week_3.downloader

import akka.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, Terminated}
import week_3.downloader.Controller.CheckUrl
import week_3.downloader.Receptionist.{Failed, Get, Result}

object Receptionist {

  case class Get(url: String)

  case class Result(url: String, links: List[String])

  case object Failed
}

class Receptionist extends Actor with ActorLogging {

  case class Job(client: ActorRef, url: String)

  var requestNumber = 0

  def runNext(queue: Vector[Job]): Receive = {
    if (queue.isEmpty) waiting
    else {
      requestNumber += 1
      val controller = context.actorOf(Props[Controller], s"ctrl-${requestNumber}")
      context.watch(controller)
      controller ! CheckUrl(queue.head.url, 2)
      working(queue)
    }
  }

  override def supervisorStrategy = SupervisorStrategy.stoppingStrategy

  override def receive: Receive = waiting

  def waiting: Receive = {
    case Get(url) =>
      log.debug("Get {}", url)
      context.become(runNext(Vector(Job(sender, url))))
  }

  def working(queue: Vector[Job]): Receive = {
    case Controller.Result(links) =>
      val job = queue.head
      log.debug(s"jobbe done... ${links}")
      job.client ! Result(job.url, links.toList)
      context.stop(context.unwatch(sender))
      context.become(runNext(queue.tail))

    case Terminated(_) =>
      val job = queue.head
      job.client ! Failed //(job)
      context.become(runNext(queue.tail))

    case Get(url) => {
      if (queue.size > 3) {
        sender ! Failed
      } else {
        val nextQueue = queue :+ Job(sender, url)
        context.become(working(nextQueue), discardOld = true)
      }
    }
  }
}
