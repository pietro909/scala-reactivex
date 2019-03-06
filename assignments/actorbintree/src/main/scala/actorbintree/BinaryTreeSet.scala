/**
  * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
  */
package actorbintree

import actorbintree.BinaryTreeNode.CopyFinished
import akka.actor._
import akka.event.LoggingReceive

import scala.concurrent.duration._
import scala.collection.immutable.Queue

object BinaryTreeSet {

  trait Operation {
    def requester: ActorRef

    def id: Int

    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection */
  case object GC

  case object Dequeue

  //case object Dequeue

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply

  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}


class BinaryTreeSet extends Actor with ActorLogging {

  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot(name: String): ActorRef =
    context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = false), name)

  var root = createRoot("FIRST_root")

  // optional
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  def normal: Receive = {
    case insert: Insert =>
      root ! insert

    case contains: Contains =>
      root ! contains

    case remove: Remove =>
      root ! remove

    case GC => {
      log.debug("GARBGE COLLECTION STARTED")
      val newRoot = createRoot("GC_root")
      root ! CopyTo(treeNode = newRoot)
      context.become(garbageCollecting(newRoot))
    }
  }

  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case CopyFinished =>
      log.info(s"garbageCollecting: CopyFinished with ${pendingQueue.length}")
      root = newRoot
      if (pendingQueue.isEmpty) {
        context.become(normal)
      } else {
        val (nextMessage, nextQueue) = pendingQueue.dequeue
        //log.info(s"garbageCollecting: start deque with ${nextMessage} to ${root.path}")
        sendOperation(nextMessage, root)
        pendingQueue = nextQueue
        context.become(dequeueing(nextMessage))
        /*
        pendingQueue foreach {
          newRoot ! _
        }
        pendingQueue = Queue.empty
        context.become(normal)
        */
      }

    case operation: Operation =>
      // log.info(s"garbageCollecting: enqueue ${operation}")
      pendingQueue = pendingQueue.enqueue(operation)
  }

  def dequeueing(operation: Operation): Receive = {
    case of: OperationFinished =>
      //log.info(s"dequeueing: OperationFinished ${operation}")
      operation.requester ! of
      //operation.requester ! result
      //log.info(s"dequeueing: OperationFinished with ${pendingQueue.length}")
      if (pendingQueue.isEmpty) {
        context.become(normal)
      } else {
        val (nextMessage, nextQueue) = pendingQueue.dequeue
        //log.info(s"dequeueing: OperationFinished start deque with ${nextMessage}")
        sendOperation(nextMessage, root)
        pendingQueue = nextQueue
        context.become(dequeueing(nextMessage))
      }

    case cr: ContainsResult =>
      //log.info(s"dequeueing: ContainsResult for ${operation} to ${operation.requester}")
      operation.requester ! cr
      //operation.requester ! result
      //log.info(s"dequeueing: ContainsResult with ${pendingQueue.length}")
      if (pendingQueue.isEmpty) {
        context.become(normal)
      } else {
        val (nextMessage, nextQueue) = pendingQueue.dequeue
        //log.info(s"dequeueing: ContainsResult continue deque with ${nextMessage}")
        sendOperation(nextMessage, root)
        pendingQueue = nextQueue
        context.become(dequeueing(nextMessage))
      }

    case operation: Operation =>
      //log.info(s"dequeueing: operation ${operation} during dequeueing")
      pendingQueue = pendingQueue.enqueue(operation)
  }

  def sendOperation(operation: Operation, target: ActorRef): Unit = {
    operation match {
      case Insert(requester, id, elem) =>
        target ! Insert(self, id, elem)
      case Remove(requester, id, elem) =>
        target ! Remove(self, id, elem)
      case Contains(requester, id, elem) =>
        target ! Contains(self, id, elem)
    }
  }
}

object BinaryTreeNode {

  trait Position

  case object Left extends Position

  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)

  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode], elem, initiallyRemoved)
}

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean) extends Actor with ActorLogging {

  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  def doLeft(f: ActorRef => Unit) = (subtrees get Left).foreach(f)

  def doRight(f: ActorRef => Unit) = (subtrees get Right).foreach(f)


  // optional
  def receive = normal

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = {
    case Insert(client, id, newElement) =>
      if (newElement == elem) {
        if (removed) {
          removed = false
          client ! OperationFinished(id)
        } else {
          // nothing to do
          //log.info(s"N: ${newElement} is me")
          client ! OperationFinished(id)
        }
      } else if (newElement > elem) {
        // RIGHT
        subtrees.get(Right) match {
          case Some(child) =>
            child ! Insert(client, id, newElement)
          case None => {
            //log.info(s"N: ${newElement} goes Right of ${elem}")
            val nextChild: ActorRef = context.actorOf(props(newElement, false), name = s"${newElement}_right")
            subtrees += ((Right, nextChild))
            client ! OperationFinished(id)
          }
        }
      } else {
        // LEFT
        subtrees.get(Left) match {
          case Some(child) =>
            child ! Insert(client, id, newElement)
          case None => {
            val nextChild: ActorRef = context.actorOf(props(newElement, false), name = s"${newElement}_left")
            subtrees += ((Left, nextChild))
            //log.info(s"N: ${newElement} goes Left of ${elem}")
            client ! OperationFinished(id)
          }
        }
      }

    case Contains(client, id, queryElement) =>
      if (elem == queryElement) {
        //log.info(s"N: ${queryElement} is contained by me")
        client ! ContainsResult(id, result = !removed)
      } else if (queryElement > elem && (subtrees get Right).isDefined) {
        doRight {
          _ ! Contains(client, id, queryElement)
        }
      } else if ((subtrees get Left).isDefined) {
        doLeft {
          _ ! Contains(client, id, queryElement)
        }
      } else {
        //log.info(s"N: ${queryElement} not found!")
        client ! ContainsResult(id, result = false)
      }

    case Remove(client, id, queryElement) =>
      if (elem == queryElement) {
        removed = true
        //log.info(s"N: remove ${queryElement} is me ${elem}")
        client ! OperationFinished(id)
      } else if (queryElement > elem && (subtrees get Right).isDefined) {
        doRight {
          _ ! Remove(client, id, queryElement)
        }
      } else if ((subtrees get Left).isDefined) {
        doLeft {
          _ ! Remove(client, id, queryElement)
        }
      } else {
        //log.info(s"N: cannot remove ${queryElement}")
        client ! OperationFinished(id)
      }

    case CopyTo(treeNode) =>
      //log.info(s"${elem} got CopyTo ")
      if (!removed) {
        treeNode ! Insert(self, elem, elem)
      }

      if (subtrees.isEmpty) {
        context.parent ! CopyFinished
        log.debug(s"    ${elem}: killing myself")
        context.stop(self)
      } else {
        doLeft {
          _ ! CopyTo(treeNode)
        }
        doRight {
          _ ! CopyTo(treeNode)
        }
        log.info(s"CopyTo: entering copying ")
        context.become(copying(subtrees.values.toSet, false), true)
      }
  }

  // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = {
    case CopyFinished =>
      val nextExpected = expected.tail
      //log.debug(s"${elem}: CopyFinished - is over? ${nextExpected.isEmpty && insertConfirmed}")
      if (nextExpected.isEmpty && insertConfirmed) {
        context.parent ! CopyFinished
        //log.debug(s"    ${elem}: killing myself")
        context.stop(self)
      } else {
        context.become(copying(nextExpected, insertConfirmed))
      }
    case OperationFinished(_) =>
      //log.debug(s"${elem}: OperationFinished - is over? ${expected.isEmpty}")
      if (expected.isEmpty) {
        context.parent ! CopyFinished
        //log.debug(s"    ${elem}: killing myself")
        context.stop(self)
      } else {
        context.become(copying(expected, insertConfirmed = true))
      }
    case fuck =>
      log.error(s"MA CHECCAZZO E' QUESTO?!?!?! ${fuck}")

  }
}
