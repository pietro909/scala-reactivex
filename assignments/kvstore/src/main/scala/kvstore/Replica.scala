package kvstore

import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, PoisonPill, Props, SupervisorStrategy, Terminated}
import kvstore.Arbiter._

import scala.collection.immutable.Queue
import akka.actor.SupervisorStrategy.Restart

import scala.annotation.tailrec
import akka.pattern.{ask, pipe}

import scala.concurrent.duration._
import scala.language.postfixOps
import akka.util.Timeout

object Replica {

  sealed trait Operation {
    def key: String

    def id: Long
  }

  case class Insert(key: String, value: String, id: Long) extends Operation

  case class Remove(key: String, id: Long) extends Operation

  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply

  case class OperationAck(id: Long) extends OperationReply

  case class OperationFailed(id: Long) extends OperationReply

  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  case class PersistenceData(key: String, valueOption: Option[String], client: ActorRef)

  case class PersistenceTimeout(id: Long)
  case class PersistenceLeadTimeout(id: Long)

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor with ActorLogging {

  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var replicators = Set.empty[ActorRef]
  // waiting for PersistenceAck
  var pending = Map.empty[Long, PersistenceData]

  val persistence = context.system.actorOf(persistenceProps)
  val scheduler = context.system.scheduler
  val TIMEOUT = 100 millis

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 5) {
    case _: Exception =>
      SupervisorStrategy.Restart
  }

  arbiter ! Join

  def receive = {
    case JoinedPrimary => context.become(leader)
    case JoinedSecondary => context.become(replica(0L))
  }

  def insertOrRemove(key: String, valueOption: Option[String], id: Long) = {
    valueOption match {
      case Some(value) =>
        kv = kv + ((key, value))
      case None =>
        kv = kv - key
    }
  }

  def persist(persistenceData: PersistenceData, id: Long) = {
    pending = pending + ((id, persistenceData))
    scheduler.scheduleOnce(TIMEOUT, self, PersistenceTimeout(id))
    persistence ! Persist(persistenceData.key, persistenceData.valueOption, id)
  }

  /* TODO Behavior for  the leader role. */
  val leader: Receive = {
    case Insert(key, value, id) =>
      insertOrRemove(key, Some(value), id)
      persist(PersistenceData(key, Some(value), sender), id)
      //pending = pending + ((id, PersistenceData(key, Some(value), sender)))
      scheduler.scheduleOnce(1 second, self, PersistenceLeadTimeout(id))
      persistence ! Persist(key, Some(value), id)

    case Remove(key, id) =>
      insertOrRemove(key, None, id)
      persist(PersistenceData(key, None, sender), id)

    case Persisted(key, seq) =>
      (pending get seq) match {
        case Some(persistenceData) =>
          persistenceData.client ! OperationAck(seq) // id = seq for 2nd replicas
          pending = pending - seq
        case None =>
          log.error(s"Got Persisted for ${key} - ${seq} but no pending found")
      }

    case PersistenceTimeout(seq) =>
      (pending get seq) match {
        case Some(persistenceData) =>
          log.debug(s"${seq} for ${persistenceData.key} was PersistenceTimeout")
          persist(persistenceData, seq)
        case None =>
        // that was done!
      }

    case PersistenceLeadTimeout(seq) =>
      (pending get seq) match {
        case Some(persistenceData) =>
          log.debug(s"${seq} for ${persistenceData.key} was PersistenceLeadTimeout")
          pending = pending - seq
          persistenceData.client ! OperationFailed(seq)
        case None =>
        // that was done!
      }

    case Get(key, id) =>
      sender ! GetResult(key, kv get key, id)

    case _ =>
  }

  /* TODO Behavior for the replica role. */
  def replica(expectedSeq: Long): Receive = {
    case Get(key, id) =>
      sender ! GetResult(key, kv get key, id)

    case Snapshot(key, valueOption, seq) =>
      if (seq == expectedSeq) {
        insertOrRemove(key, valueOption, seq)
        persist(PersistenceData(key, valueOption, sender), seq)
        context.become(replica(expectedSeq + 1))
      } else if (seq < expectedSeq) {
        sender ! SnapshotAck(key, seq)
        context.become(replica(seq + 1))
      }
    // if greater than expected, ignore it

    case Persisted(key, seq) =>
      (pending get seq) match {
        case Some(persistenceData) =>
          persistenceData.client ! SnapshotAck(key, seq) // id = seq for 2nd replicas
          pending = pending - seq
        case None =>
          log.error(s"Got Persisted for ${key} - ${seq} but no pending found")
      }

    case PersistenceTimeout(seq) =>
      (pending get seq) match {
        case Some(persistenceData) =>
          persist(persistenceData, seq)
        case None =>
          // no op
      }
    case _ =>
  }

}

