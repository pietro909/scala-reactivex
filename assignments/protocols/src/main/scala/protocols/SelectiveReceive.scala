package protocols

import akka.actor.UnhandledMessage
import akka.actor.typed.{ActorContext, _}
import akka.actor.typed.scaladsl._

object SelectiveReceive {
  /**
    * @return A behavior that stashes incoming messages unless they are handled
    *         by the underlying `initialBehavior`
    * @param bufferSize      Maximum number of messages to stash before throwing a `StashOverflowException`
    *                        Note that 0 is a valid size and means no buffering at all (ie all messages should
    *                        always be handled by the underlying behavior)
    * @param initialBehavior Behavior to decorate
    * @tparam T Type of messages
    *
    *           Hint: Implement an [[ExtensibleBehavior]], use a [[StashBuffer]] and [[Behavior]] helpers such as `start`,
    *           `validateAsInitial`, `interpretMessage`,`canonicalize` and `isUnhandled`.
    */
  def apply[T](bufferSize: Int, initialBehavior: Behavior[T]): Behavior[T] = {
    new ExtensibleBehavior[T] {

      import akka.actor.typed.Behavior.{validateAsInitial, interpretMessage, start, canonicalize}

      // println(s"Starting with ${bufferSize}")

      val stashBuffer: StashBuffer[T] = StashBuffer(bufferSize)

      override def receive(ctx: ActorContext[T], msg: T): Behavior[T] = {
        println(s"[SelectiveReceive] ${msg}")
        //println(stashBuffer)
        val started: Behavior[T] = validateAsInitial(start(initialBehavior, ctx))
        val next: Behavior[T] = interpretMessage(started, ctx, msg)
        if (Behavior.isUnhandled(next)) {
          println(s"  ${msg} is unhandled")
          stashBuffer.stash(msg)
          Behaviors.same
          //canonicalize(next, started, ctx)
        } else if (stashBuffer.nonEmpty) {
          // TODO: run the messages
          //println(s"  stashing everybody...")
          //SelectiveReceive(bufferSize, stashBuffer.unstashAll(ctx.asScala, next))
          stashBuffer.unstashAll(ctx.asScala, SelectiveReceive(bufferSize, canonicalize(next, started, ctx)))
        } else {
          //println(s"  next! ${next}")
          //SelectiveReceive(bufferSize, next)
          next
        }
      }

      override def receiveSignal(ctx: ActorContext[T], msg: Signal): Behavior[T] = {
        //println(s"receiveSignal! ${msg}")
        initialBehavior
      }
    }
  }
}
