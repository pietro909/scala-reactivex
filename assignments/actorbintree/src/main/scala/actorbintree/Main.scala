package actorbintree

import actorbintree.BinaryTreeSet.{Insert, OperationFinished}
import akka.actor.{Actor, Props}

class Main extends Actor {
  val binaryTree = context.actorOf(Props[BinaryTreeSet], "binary_tree")

  binaryTree ! Insert(self, id = 1, elem = 4)
  binaryTree ! Insert(self, id = 2, elem = 10)
  binaryTree ! Insert(self, id = 3, elem = 2)
  binaryTree ! Insert(self, id = 3, elem = 25)
  binaryTree ! Insert(self, id = 3, elem = 1)


  def receive = {
    case _ =>
      println(s"pareeent")
      //context.stop(self)
  }
}
