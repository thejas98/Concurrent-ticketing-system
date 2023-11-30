package app

import actors.EventManagement
import actors.PersistentEventManager.Command
import akka.NotUsed
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object TicketAgencyApp {
  def main(args: Array[String]): Unit = {
    trait RootCommand
    case class RetrieveEventManagerActor(replyTo: ActorRef[ActorRef[Command]]) extends RootCommand

    val rootBehavior: Behavior[RootCommand] = Behaviors.setup { context =>
      val EventManagerActor = context.spawn(EventManagement(), "Event-Manager")

      Behaviors.receiveMessage {
        case RetrieveEventManagerActor(replyTo) =>
          replyTo ! EventManagerActor
          Behaviors.same
      }
    }

    implicit val system: ActorSystem[RootCommand] = ActorSystem(rootBehavior, "TicketAgencySystem")
    implicit val timeout: Timeout = Timeout(5.seconds)

    val eventManagerActorFuture: Future[ActorRef[Command]] = system.ask(replyTo => RetrieveEventManagerActor(replyTo))
    eventManagerActorFuture
  }
}
