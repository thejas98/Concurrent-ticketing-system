package com.csye7200.cts.app

import akka.NotUsed
import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.util.Timeout
import com.csye7200.cts.actors.Event.EventCommand
import com.csye7200.cts.actors.TicketActor.TicketSellerCommand
import com.csye7200.cts.actors.{EventManager, TicketManagerActor}
import com.csye7200.cts.http.TicketAgencyRouter

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object TicketAgencyApp {
  def startHttpServer(eventManager: ActorRef[EventCommand], ticketManager: ActorRef[TicketSellerCommand])(implicit system: ActorSystem[_]): Unit = {
    implicit val ec: ExecutionContext = system.executionContext
    val router = new TicketAgencyRouter(eventManager, ticketManager)
    val routes = router.routes

    val httpBindingFuture = Http().newServerAt("localhost", 8080).bind(routes)
    httpBindingFuture.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info(s"Server online at http://${address.getHostString}:${address.getPort}")
      case Failure(ex) =>
        system.log.error(s"Failed to bind HTTP server, because: $ex")
        system.terminate()
    }
  }
  def main(args: Array[String]): Unit = {
    trait RootCommand
    case class RetrieveEventManagerActor(replyTo: ActorRef[ActorRef[EventCommand]]) extends RootCommand
    case class RetrieveTicketManagerActor(replyTo: ActorRef[ActorRef[TicketSellerCommand]]) extends RootCommand
    val rootBehavior: Behavior[RootCommand] = Behaviors.setup { context =>
      val eventManagerActor = context.spawn(EventManager(), "Event-Manager")
      val ticketManagerActor = context.spawn(TicketManagerActor(), name="Inventory-Manager")

      Behaviors.receiveMessage {
        case RetrieveEventManagerActor(replyTo) =>
          replyTo ! eventManagerActor
          Behaviors.same
        case RetrieveTicketManagerActor(replyTo) =>
          replyTo ! ticketManagerActor
          Behaviors.same
      }
    }

    implicit val system: ActorSystem[RootCommand] = ActorSystem(rootBehavior, "TicketAgencySystem")
    implicit val timeout: Timeout = Timeout(5.seconds)

    val eventManagerActorFuture: Future[ActorRef[EventCommand]] = system.ask(replyTo => RetrieveEventManagerActor(replyTo))
    val ticketManagerActorFuture: Future[ActorRef[TicketSellerCommand]] = system.ask(replyTo => RetrieveTicketManagerActor(replyTo))

    val eventManagerActor = Await.result(eventManagerActorFuture, 5.seconds)
    val ticketManagerActor = Await.result(ticketManagerActorFuture, 5.seconds)

    startHttpServer(eventManagerActor, ticketManagerActor)


  }
}

