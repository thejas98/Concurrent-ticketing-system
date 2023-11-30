package actors

import actors.TicketActor.TicketSellerResponse.NoSuchTicketResponse
import akka.NotUsed
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.persistence.Persistence
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout

import scala.concurrent.{Await, ExecutionContext, Future}
import java.util.UUID

object TicketManagerActor {

  import TicketActor.TicketSellerCommand
  import TicketActor.TicketSellerCommand._
  import TicketActor.TicketSellerCommand._

  // events
  sealed trait Event

  case class TicketsCreated(id: String) extends Event

  // state
  case class State(tickets: Map[String, ActorRef[TicketSellerCommand]])

  def commandHandler(context: ActorContext[TicketSellerCommand]): (State, TicketSellerCommand) => Effect[Event, State] = (state, command) => {
    println("state: " + state.tickets)
    command match {
      case buyCommand@BuyTicket(_, _, _, _) =>
        val id = "BookingID-" + UUID.randomUUID().toString
        val newPurchase = context.spawn(TicketActor(id), id)
        Effect
          .persist(TicketsCreated(id))
          .thenReply(newPurchase)(_ => buyCommand)
      case cancelCommand@CancelTicket(ticketID, replyToTicketManager) =>
        state.tickets.get(ticketID) match {
          case Some(ticketActor) =>
            Effect.reply(ticketActor)(cancelCommand)
          case None =>
            Effect.reply(replyToTicketManager)(NoSuchTicketResponse(None))
        }
    }
  }

  def eventHandler(context: ActorContext[TicketSellerCommand]): (State, Event) => State = (state, event) => {
    event match {
      case TicketsCreated(id) =>
        val tickets = context.child(id)
          .getOrElse(context.spawn(TicketActor(id), id))
          .asInstanceOf[ActorRef[TicketSellerCommand]]
        state.copy(state.tickets + (id -> tickets))

    }
  }

  def apply(): Behavior[TicketSellerCommand] = Behaviors.setup {
    context => {
      EventSourcedBehavior[TicketSellerCommand, Event, State](
        persistenceId = PersistenceId.ofUniqueId("InventoryManager"),
        emptyState = State(Map()),
        commandHandler = commandHandler(context),
        eventHandler = eventHandler(context)
      )
    }
  }
}


object TicketSellerPlayGround {

  import TicketActor.TicketSellerCommand._
  import TicketActor.TicketSellerResponse._
  import TicketActor.TicketSellerResponse
  import TicketActor.TicketSellerCommand._
  import Event.EventCommand._
  import Event.EventResponse
  import Event.EventResponse._

  def main(args: Array[String]): Unit = {
    val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context =>

      val eventManager = context.spawn(EventManager(), "Event-Manager")
      val inventory = context.spawn(TicketManagerActor(), "Ticket-Manager")
      val responseHandlerInventory = context.spawn(Behaviors.receiveMessage[TicketSellerResponse] {
        case PurchaseResponse(ticket) =>
          println()
          println("purchase  ticket id: " + ticket.get.ticketID + " " + ticket.get.ticketStatus)
          println()
          Behaviors.same
        case CancellationResponse(tickets) =>
          tickets match {
            case Some(ticket) =>
              println()
              println("cancelled ticket id: " + ticket.ticketID)
              println()
              Behaviors.same
            case None =>
              println()
              println("Ticket cannot be cancelled")
              println()
              Behaviors.same
          }
      }, "replyHandlerInventory")

      val responseHandler = context.spawn(Behaviors.receiveMessage[EventResponse] {
        case EventCreatedResponse(eventId) =>
          println("event created event ID:" + eventId)
          Behaviors.same
        case GetEventResponse(maybeEvent) =>
          println()
          println("get event Event Max Tickets: " + maybeEvent.get.eventName + " " + maybeEvent.get.maxTickets)
          println()
          Behaviors.same
      }, "replyHandler")


      // Testing eventmanagement
      //      eventManager ! CreateEvent("Celtics Match", "Arena A", "Music Corp", 50.0, 1000, "2023-12-15 19:00:00", 120, responseHandler)
      eventManager ! GetEvent("EventID-dbca9341-dbd6-402d-bd8b-02cef6997fcb", responseHandler)

      //       test 1
      inventory ! BuyTicket("EventID-dbca9341-dbd6-402d-bd8b-02cef6997fcb", 100, "Michael Jordan Testing23233234", responseHandlerInventory)
      inventory ! CancelTicket("BookingID-7c6ce924-31a3-448e-883c-a88da565e846", responseHandlerInventory)
      eventManager ! GetEvent("EventID-dbca9341-dbd6-402d-bd8b-02cef6997fcb", responseHandler)
      Behaviors.empty
    }
    val system = ActorSystem(rootBehavior, "TicketSellerDemo")
  }
}

