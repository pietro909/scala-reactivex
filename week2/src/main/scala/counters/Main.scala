package counters

import akka.actor.{Actor, Props}

class Main extends Actor {
  val counter = context.actorOf(Props[Counter], "counters")

  counter ! "incr"
  counter ! "incr"
  counter ! "incr"
  counter ! "get"

  def receive = {
    case count: Int =>
      println(s"count is $count")
      context.stop(self)
  }
}
