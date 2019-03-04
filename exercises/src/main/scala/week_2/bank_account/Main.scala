package week_2.bank_account

import akka.actor.{Actor, ActorLogging, Props}
import week_2.bank_account.BankAccount.{Deposit}
import week_2.bank_account.WireTransfer.{Done, Failed, Transfer}

class Main extends Actor with ActorLogging {
  val transferer = context.actorOf(Props[WireTransfer], "transferer")
  val account1 = context.actorOf(Props[BankAccount], "account_1")
  val account2 = context.actorOf(Props[BankAccount], "account_2")

  account1 ! Deposit(20)
  account2 ! Deposit(70)
  transferer ! Transfer(account2, account1, 30)

  def receive = {
    case Done =>
      println(s"Done!")
      context.stop(self)

    case Failed =>
      context.stop(self)
  }
}
