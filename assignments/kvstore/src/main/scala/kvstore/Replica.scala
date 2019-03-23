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

  case class ReplicatorData(replicatorRef: ActorRef, outstandingMessages: List[Long])

  case class PendingItem(client: ActorRef, toBeReplicated: Int)

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
  // waiting for PersistenceAck
  private var pendingPersistence = Map.empty[Long, PersistenceData]

  private val persistence = context.system.actorOf(persistenceProps)
  private val scheduler = context.system.scheduler
  private val TIMEOUT = 100 millis

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 5) {
    case _: Exception =>
      SupervisorStrategy.Restart
  }

  arbiter ! Join

  def receive = {
    case JoinedPrimary => context.become(leader(Map.empty[ActorRef, ReplicatorData], Map.empty[Long, PendingItem]))
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
    pendingPersistence = pendingPersistence + ((id, persistenceData))
    scheduler.scheduleOnce(TIMEOUT, self, PersistenceTimeout(id))
    persistence ! Persist(persistenceData.key, persistenceData.valueOption, id)
  }

  /*def waitingForReplication(id: Long, replicators: Map[ActorRef, ReplicatorData]): Boolean =
    replicators.foldLeft(false) {
      case (found: Boolean, (_, replicatorData)) =>
      found || replicatorData.outstandingMessages.contains(id)
    }
    */

  /* TODO Behavior for  the leader role. */
  def leader(replicators: Map[ActorRef, ReplicatorData], pending: Map[Long, PendingItem]): Receive = {
    case Insert(key, value, id) =>
      insertOrRemove(key, Some(value), id)
      persist(PersistenceData(key, Some(value), sender), id)
      scheduler.scheduleOnce(1 second, self, PersistenceLeadTimeout(id))
      val newReplicators = replicators.map(data => {
        val (replicaRef, replicatorData) = data
        replicatorData.replicatorRef ! Replicate(key, Some(value), id)
        // persist the outstanding message
        val newMessages = id :: replicatorData.outstandingMessages
        (replicaRef, ReplicatorData(replicatorData.replicatorRef, newMessages))
      })
      val newPending = pending + ((id, PendingItem(sender, newReplicators.size)))
      log.debug(s"${id} Insert has newPending ${newPending}")
      context.become(leader(newReplicators, newPending), true)

    case Remove(key, id) =>
      insertOrRemove(key, None, id)
      persist(PersistenceData(key, None, sender), id)
      val newReplicators = replicators.map(data => {
        val (replicaRef, replicatorData) = data
        replicatorData.replicatorRef ! Replicate(key, None, id)
        // persist the outstanding message
        val newMessages = id :: replicatorData.outstandingMessages
        (replicaRef, ReplicatorData(replicatorData.replicatorRef, newMessages))
      })
      val newPending = pending + ((id, PendingItem(sender, newReplicators.size)))
      log.debug(s"${id} Remove has newPending ${newPending}")
      context.become(leader(newReplicators, newPending), true)

    case Persisted(key, seq) =>
      pendingPersistence get seq match {
        case Some(persistenceData) =>
          pending get seq match {
            case None =>
              persistenceData.client ! OperationAck(seq) // id = seq for 2nd replicas
            case Some(PendingItem(client, toBeReplicated)) if toBeReplicated < 1 =>
              val newPending = pending - seq
              persistenceData.client ! OperationAck(seq) // id = seq for 2nd replicas
              context.become(leader(replicators, newPending), true)
            case _ =>
              log.debug(s"${seq} persisted, waiting for ${pending}")

            // just wait
          }
          pendingPersistence = pendingPersistence - seq
        case None =>
        // just wait
      }

    case PersistenceTimeout(seq) =>
      pendingPersistence get seq match {
        case Some(persistenceData) =>
          log.debug(s"${seq} for ${persistenceData.key} was PersistenceTimeout")
          persist(persistenceData, seq)
        case None =>
        // that was done!
      }

    case PersistenceLeadTimeout(seq) =>
      pendingPersistence get seq match {
        case Some(persistenceData) =>
          log.debug(s"${seq} for ${persistenceData.key} was PersistenceLeadTimeout")
          pendingPersistence = pendingPersistence - seq
          persistenceData.client ! OperationFailed(seq)
        case None =>
        // that was done!
      }
      pending get seq match {
        case Some(PendingItem(client, toBeReplicated)) if toBeReplicated > 0 =>
          log.debug(s"${seq} with ${toBeReplicated} was PersistenceLeadTimeout")
          client ! OperationFailed(seq)
          context.become(leader(replicators, pending - seq), true)
        case Some(PendingItem(client, _))  =>
          context.become(leader(replicators, pending - seq), true)
        case None =>
        // that was done!
      }


    case Get(key, id) =>
      sender ! GetResult(key, kv get key, id)

    case Replicas(replicas) =>
      val secondaryReplicas = replicas.filterNot(_ == context.self)
      var newReplicators = Map.empty[ActorRef, ReplicatorData]
      var newPending = pending
      // remove the non existing ones
      replicators foreach { data =>
        if (secondaryReplicas.contains(data._1)) {
          newReplicators += data
        } else {
          data._2.replicatorRef ! PoisonPill
          // TODO: if hits 0 and no persististence is pending, send the hack
          newPending = newPending.map(p => {
            val (pId, pItem) = p
            if (pItem.toBeReplicated < 2) {
              pItem.client ! OperationAck(pId)
            }
            (pId, PendingItem(pItem.client, pItem.toBeReplicated -1 ))
          })
        }
      }
      // add the new ones
      secondaryReplicas foreach { replicaRef =>
        if (!newReplicators.contains(replicaRef)) {
          val replicator = context.system.actorOf(Replicator.props(replicaRef))
          kv.foreach(keyValue =>
            replicator ! Replicate(keyValue._1, Some(keyValue._2), 0)
          )
          newReplicators += ((replicaRef, ReplicatorData(replicator, List.empty[Long])))
        }
      }
      log.debug(s"Replicas, new has newPending ${newPending}")

      context.become(leader(newReplicators, newPending), discardOld = true)

    case Replicated(key, id) =>
      val newReplicators = replicators.map(data => {
        val (replicaRef, replicatorData) = data
        (replicaRef, ReplicatorData(replicatorData.replicatorRef, replicatorData.outstandingMessages.filterNot(_ == id)))
      })
      // TODO: if "id" is not found in the replicators, send OperationAck
      (pending get id, pendingPersistence.contains(id)) match {
        case (Some(PendingItem(client, toBeReplicated)), false) if toBeReplicated < 2 =>
          client ! OperationAck(id)
          context.become(leader(newReplicators, pending - id), discardOld = true)
        case (Some(PendingItem(client, toBeReplicated)), any) =>
          log.debug(s"${id} had ${toBeReplicated}, pending persistence is ${any}")

          val newPending = pending.updated(id, PendingItem(client, toBeReplicated - 1))
          context.become(leader(newReplicators, newPending), discardOld = true)
        case _ =>
          log.debug(s"${id} deep shit")

        // this is weird...
      }
    case _ =>
    // just wait

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
      pendingPersistence get seq match {
        case Some(persistenceData) =>
          persistenceData.client ! SnapshotAck(key, seq) // id = seq for 2nd replicas
          pendingPersistence = pendingPersistence - seq
        case None =>
          log.error(s"Got Persisted for ${
            key
          } - ${
            seq
          } but no pending found")
      }

    case PersistenceTimeout(seq) =>
      pendingPersistence get seq match {
        case Some(persistenceData) =>
          persist(persistenceData, seq)
        case None =>
        // no op
      }
    case _ =>
  }

}

