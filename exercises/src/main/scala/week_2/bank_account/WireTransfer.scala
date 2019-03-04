package week_2.bank_account

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorRef}
import week_2.bank_account.BankAccount.{Deposit, Withdraw}
import week_2.bank_account.WireTransfer.{Done, Failed, Transfer}

object WireTransfer {

  case class Transfer(from: ActorRef, to: ActorRef, amount: BigInt)

  case object Done

  case object Failed

}

class WireTransfer extends Actor {

  def receive: Receive = {
    case Transfer(from, to, amount) =>
      from ! Withdraw(amount)
      context.become(awaitWithdraw(to, amount, sender), discardOld = false)
  }

  def awaitWithdraw(to: ActorRef, amount: BigInt, sender: ActorRef): Receive = {
    case Done =>
      to ! Deposit(amount)
      context.become(awaitDeposit(sender), discardOld = false)

    case Failed =>
      sender ! Failed
      context.stop(self)
  }

  def awaitDeposit(sender: ActorRef): Receive = {
    case Done =>
      sender ! Done
      context.stop(self)
  }
}
