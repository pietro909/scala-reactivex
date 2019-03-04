package week_2.bank_account

import akka.actor.Actor
import week_2.bank_account.WireTransfer.{Done, Failed}

object BankAccount {

  case class Deposit(amount: BigInt) {
    require(amount > 0)
  }

  case class Withdraw(amount: BigInt) {
    require(amount > 0)
  }

}

class BankAccount extends Actor {

  import BankAccount._

  var balance = BigInt(0)

  def receive: Receive = {
    case Deposit(amount) =>
      balance += amount
      sender ! Done

    case Withdraw(amount) if amount <= balance =>
      balance -= amount
      sender ! Done

    case _ =>
      sender ! Failed
  }
}