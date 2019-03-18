package kvstore

import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorRef

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

object Replicator {
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class Replicated(key: String, id: Long)
  
  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)

  case class SnapshotAckMissed(seq: Long)

  def props(replica: ActorRef): Props = Props(new Replicator(replica))
}

class Replicator(val replica: ActorRef) extends Actor {
  import Replicator._
  import Replica._
  import context.dispatcher
  
  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (ActorRef, Replicate)]
  // a sequence of not-yet-sent snapshots (you can disregard this if not implementing batching)
  var pending = Vector.empty[Snapshot]

  def receive = normal(0)

  implicit val executionContext: ExecutionContext = context.dispatcher
  
  /* TODO Behavior for the Replicator. */
  def normal(nextSeq: Long): Receive = {
    case Replicate(key, valueOption, id) =>
      acks = acks + ((nextSeq, (sender, Replicate(key, valueOption, id))))
      replica ! Snapshot(key, valueOption, nextSeq)
      context.system.scheduler.scheduleOnce(200 millis, self, SnapshotAckMissed(nextSeq))
      context.become(normal(nextSeq+1))
    case SnapshotAck(key, seq) =>
      // this should arrive BEFORE 200ms have passed from corresponding Replicate
      acks get seq match {
        case Some((sender, Replicate(key, _, id))) =>
          sender ! Replicated(key, id)
        case None =>
          // no op
      }
      acks = acks - seq
    case SnapshotAckMissed(seq) =>
      acks get seq match {
        case Some((sender, Replicate(key, valueOption, id))) =>
          replica ! Snapshot(key, valueOption, seq)
          context.system.scheduler.scheduleOnce(200 millis, self, SnapshotAckMissed(seq))
        case None =>
          // if is not there anymore, it means that SnapshotAck was received
      }
    case _ =>
  }

}
