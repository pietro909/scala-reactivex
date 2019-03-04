package week_2.counters

import akka.actor.{Actor, ActorRef}

class Counter extends Actor {
  def counter(n: Int): Receive = {
    case "incr" => context.become(counter(n+1), discardOld = false)
    case "get" => sender ! n
  }

  def receive = counter(0)
}
