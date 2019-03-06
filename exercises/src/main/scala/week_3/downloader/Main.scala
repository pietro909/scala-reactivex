package week_3.downloader

import akka.actor.{Actor, Props}
import week_3.downloader.Receptionist.{Get, Result, Failed}

class Main extends Actor {
  val receptionist = context.actorOf(Props[Receptionist])

  receptionist ! Get("https://www.assimil.it")

  def receive = {
    case Result(url, links) =>
      println(s"Links found: ")
      println(links.mkString(", \n"))
      context.stop(self)
    case Failed =>
      println(s"FAILED")

  }
}
