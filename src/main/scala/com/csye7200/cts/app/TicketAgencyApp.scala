package com.csye7200.cts.app

import akka.NotUsed
import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.util.Timeout
import com.csye7200.cts.actors.Customer.CustomerCommand
import com.csye7200.cts.actors.Event.EventCommand
import com.csye7200.cts.actors.TicketActor.TicketSellerCommand
import com.csye7200.cts.actors.{CustomerManager, EventManager, TicketManagerActor}
import com.csye7200.cts.http.TicketAgencyRouter


import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object TicketAgencyApp {
  def startHttpServer(eventManager: ActorRef[EventCommand], ticketManager: ActorRef[TicketSellerCommand], customerManager: ActorRef[CustomerCommand])(implicit system: ActorSystem[_]): Unit = {
    implicit val ec: ExecutionContext = system.executionContext
    val router = new TicketAgencyRouter(eventManager, ticketManager, customerManager)
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
    case class RetrieveCustomerManagerActor(replyTo: ActorRef[ActorRef[CustomerCommand]]) extends RootCommand

    val rootBehavior: Behavior[RootCommand] = Behaviors.setup { context =>
      val eventManagerActor = context.spawn(EventManager(), "Event-Manager")
      val ticketManagerActor = context.spawn(TicketManagerActor(), name="Ticket-Manager")
      val customerManagerActor = context.spawn(CustomerManager(), name="Customer-Manager")

      Behaviors.receiveMessage {
        case RetrieveEventManagerActor(replyTo) =>
          replyTo ! eventManagerActor
          Behaviors.same
        case RetrieveTicketManagerActor(replyTo) =>
          replyTo ! ticketManagerActor
          Behaviors.same
        case RetrieveCustomerManagerActor(replyTo) =>
          replyTo ! customerManagerActor
          Behaviors.same
      }
    }

    implicit val system: ActorSystem[RootCommand] = ActorSystem(rootBehavior, "TicketAgencySystem")
    implicit val timeout: Timeout = Timeout(5.seconds)

    val eventManagerActorFuture: Future[ActorRef[EventCommand]] = system.ask(replyTo => RetrieveEventManagerActor(replyTo))
    val ticketManagerActorFuture: Future[ActorRef[TicketSellerCommand]] = system.ask(replyTo => RetrieveTicketManagerActor(replyTo))
    val customerManagerActorFuture: Future[ActorRef[CustomerCommand]] = system.ask(replyTo => RetrieveCustomerManagerActor(replyTo))

//    val eventManagerActor = Await.result(eventManagerActorFuture, 5.seconds)
//    val ticketManagerActor = Await.result(ticketManagerActorFuture, 5.seconds)
//    val customerManagerActor = Await.result(customerManagerActorFuture, 5.seconds)
    import scala.concurrent.ExecutionContext.Implicits.global

    val allFutures: Seq[Future[Any]] = Seq(eventManagerActorFuture, ticketManagerActorFuture, customerManagerActorFuture)
    val futureResult = Future.sequence(allFutures)
    futureResult.onComplete{
      case Success(futureResults) =>
        val eventManagerActor = futureResults(0).asInstanceOf[ActorRef[EventCommand]]
        val ticketManagerActor = futureResults(1).asInstanceOf[ActorRef[TicketSellerCommand]]
        val customerManagerActor = futureResults(2).asInstanceOf[ActorRef[CustomerCommand]]

        startHttpServer(eventManagerActor, ticketManagerActor, customerManagerActor)

      case Failure(exception) =>
        println(s"$exception: Failed to start http server")
    }



  }
}

