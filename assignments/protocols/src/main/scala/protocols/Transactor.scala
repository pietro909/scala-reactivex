package protocols

import akka.actor.typed._
import akka.actor.typed.scaladsl._

import scala.concurrent.duration._

object Transactor {

  sealed trait PrivateCommand[T] extends Product with Serializable

  final case class Committed[T](session: ActorRef[Session[T]], value: T) extends PrivateCommand[T]

  final case class RolledBack[T](session: ActorRef[Session[T]]) extends PrivateCommand[T]

  sealed trait Command[T] extends PrivateCommand[T]

  final case class Begin[T](replyTo: ActorRef[ActorRef[Session[T]]]) extends Command[T]

  sealed trait Session[T] extends Product with Serializable

  final case class Extract[T, U](f: T => U, replyTo: ActorRef[U]) extends Session[T]

  final case class Modify[T, U](f: T => T, id: Long, reply: U, replyTo: ActorRef[U]) extends Session[T]

  final case class Commit[T, U](reply: U, replyTo: ActorRef[U]) extends Session[T]

  final case class Rollback[T]() extends Session[T]

  /**
    * @return A behavior that accepts public [[Command]] messages. The behavior
    *         should be wrapped in a [[SelectiveReceive]] decorator (with a capacity
    *         of 30 messages) so that beginning new sessions while there is already
    *         a currently running session is deferred to the point where the current
    *         session is terminated.
    * @param value          Initial value of the transactor
    * @param sessionTimeout Delay before rolling back the pending modifications and
    *                       terminating the session
    */
  def apply[T](value: T, sessionTimeout: FiniteDuration): Behavior[Command[T]] =
    SelectiveReceive[Command[T]](30, initialBehavior = idle(value, sessionTimeout).narrow)

  /**
    * @return A behavior that defines how to react to any [[PrivateCommand]] when the transactor
    *         has no currently running session.
    *         [[Committed]] and [[RolledBack]] messages should be ignored, and a [[Begin]] message
    *         should create a new session.
    * @param value          Value of the transactor
    * @param sessionTimeout Delay before rolling back the pending modifications and
    *                       terminating the session
    *
    *                       Hints:
    *   - When a [[Begin]] message is received, an anonymous child actor handling the session should be spawned,
    *   - In case the child actor is terminated, the session should be rolled back,
    *   - When `sessionTimeout` expires, the session should be rolled back,
    *   - After a session is started, the next behavior should be [[inSession]],
    *   - Messages other than [[Begin]] should not change the behavior.
    */
  private def idle[T](value: T, sessionTimeout: FiniteDuration): Behavior[PrivateCommand[T]] =
    Behaviors.receive[PrivateCommand[T]]({
      case (ctx, Begin(replyTo)) =>
        val sessionRef: ActorRef[Session[T]] = ctx.spawnAnonymous(sessionHandler(value, ctx.self, Set.empty[Long]))
        ctx.watch(sessionRef)
        ctx.schedule(sessionTimeout, sessionRef.narrow, Rollback[T]())
        replyTo ! sessionRef
        val inSessionRef = inSession(value, sessionTimeout, sessionRef)
        println(s"\t[idle] Entering inSession @ ${inSessionRef}")
        SelectiveReceive[PrivateCommand[T]](30, inSessionRef)

      case (ctx, x) =>
        println(s"\t[idle] Uknown ${x}")
        Behaviors.same
    })

  /**
    * @return A behavior that defines how to react to [[PrivateCommand]] messages when the transactor has
    *         a running session.
    *         [[Committed]] and [[RolledBack]] messages should commit and rollback the session, respectively.
    *         [[Begin]] messages should be unhandled (they will be handled by the [[SelectiveReceive]] decorator).
    * @param rollbackValue  Value to rollback to
    * @param sessionTimeout Timeout to use for the next session
    * @param sessionRef     Reference to the child [[Session]] actor
    */
  private def inSession[T](rollbackValue: T, sessionTimeout: FiniteDuration, sessionRef: ActorRef[Session[T]]): Behavior[PrivateCommand[T]] =
    Behaviors.receive[PrivateCommand[T]]({
      case (ctx, Committed(session, value)) =>
        println(s"\t[inSession] Committed ${session} ${value}")
        ctx.stop(session)
        if (session == sessionRef) {
          println(s"[inSession] entering idle with value ${value}")
          idle(value, sessionTimeout)
        } else {
          Behaviors.same
        }

      case (ctx, RolledBack(session)) =>
        println(s"\t[inSession]RolledBack ${session}")
        ctx.stop(session)
        if (session == sessionRef) {
          println(s"\t[inSession] entering idle")
          idle(rollbackValue, sessionTimeout)
        } else {
          Behaviors.same
        }

      case (ctx, b: Begin[T]) =>
        println(s"\t[inSession] unhandled Begin")
        Behaviors.unhandled

      case (ctx, x) =>
        println(s"[inSession] Uknown ${x}")
        Behaviors.same
    })

  /**
    * @return A behavior handling [[Session]] messages. See in the instructions
    *         the precise semantics that each message should have.
    * @param currentValue The session’s current value
    * @param commit       Parent actor reference, to send the [[Committed]] message to
    * @param done         Set of already applied [[Modify]] messages
    */
  private def sessionHandler[T](currentValue: T, commit: ActorRef[Committed[T]], done: Set[Long]): Behavior[Session[T]] =
    Behaviors.receive[Session[T]]({
      /*
      apply the given projector function to the current Transactor value (possibly modified by the current session) and
      return the result to the given ActorRef
       */
      case (ctx, Extract(f, replyTo)) =>
        println(s"\t[sessionHandler] Extract")
        val u = f(currentValue)
        println(s"\t[sessionHandler] ${currentValue} => ${u}")
        replyTo.narrow.tell(u)
        Behaviors.same

      /*
      calculate a new value base on function 'f' and return the given reply value to the given replyTo ActorRef
       */
      case (ctx, Modify(f, id, reply, replyTo)) =>
        println(s"\t[sessionHandler] Modify ${id}")
        if (done.contains(id)) {
          replyTo.narrow.tell(reply)
          Behaviors.same
        } else {
          val nextValue = f(currentValue)
          replyTo.narrow.tell(reply)
          sessionHandler(nextValue, commit, done + id)
        }

      /*
       terminate the current session, committing the performed modifications and thus making the modified value
       available to the next session; the given reply is sent to the given ActorRef as confirmation
       */
      case (ctx, Commit(reply, replyTo)) =>
        println(s"\t[sessionHandler] Commit ${reply}")
        commit.tell(Committed(ctx.self, currentValue))
        println(s"\t[sessionHandler] Committed to ${commit}")
        replyTo.narrow.tell(reply)
        //ctx.stop(ctx.self)
        Behaviors.stopped //(Behaviors.unhandled)
      //Behaviors.same

      /*
        terminate the current session rolling back all modifications,
        i.e. the next session will see the same value that this session saw when it started
       */
      case (ctx, Rollback()) =>
        println(s"\t[sessionHandler] Rollback")
        // where do I send it?
        //Behaviors.stopped(Behaviors.unhandled)
        Behaviors.same

      case x =>
        println(s"\t[sessionHandler] Uknown ${x}")
        Behaviors.unhandled
    })
}
