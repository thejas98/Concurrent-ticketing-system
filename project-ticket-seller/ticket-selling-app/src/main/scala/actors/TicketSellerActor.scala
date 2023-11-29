package actors

import actors.TicketSellerActor.TicketsState
import akka.NotUsed
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.persistence.Persistence
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import java.util.UUID
import scala.util.{Failure, Success, Try}

object TicketSellerActor {
  // Commands
  sealed trait TicketSellerCommand

  object TicketSellerCommand {
    case class BuyTicket(eventId: String, numOfTickets: Int, customerID: String, replyToInventory: ActorRef[TicketSellerResponse]) extends TicketSellerCommand
    case class CancelTicket(ticketID: String, replyToInventory: ActorRef[TicketSellerResponse]) extends TicketSellerCommand
    case class GetBookingReference()
  }

  // Events
  sealed trait TicketSellerEvent
  case class TicketPurchased(ticketsState: TicketsState) extends TicketSellerEvent
  case class TicketCancelled(changeStatus: String) extends TicketSellerEvent

  // Responses
  sealed trait TicketSellerResponse
  object TicketSellerResponse {
    case class PurchaseResponse(tickets: TicketsState) extends TicketSellerResponse
    case class CancellationResponse(tickets: TicketsState) extends TicketSellerResponse
  }

  // State
  case class TicketsState(ticketID: String, eventID: String, numberOfTickets: Int, ticketStatus: String, customerID: String)

  // available tickets
  case class AvailableTickets(value: Int)
  import TicketSellerActor.TicketSellerResponse._
  import actors.TicketSellerActor.TicketSellerCommand._

  // Command Handler
  // create booking id in command handler rather than apply method
  def commandHandler(context: ActorContext[TicketSellerCommand]): (TicketsState, TicketSellerCommand) => Effect[TicketSellerEvent, TicketsState] = (state, command) => {

    import PersistentEventManager.Response
    import PersistentEventManager.Response._
    import PersistentEventManager.Command._

    command match {
      case BuyTicket(eventId, numOfTickets, customerID, inventory) =>
        val id = state.ticketID
        val eventManager = context.spawn(EventManagement(), "checkAvailability")
        val eventManagerUpdate = context.spawn(EventManagement(), "updateAvailability")

        val responseHandler = context.spawn(Behaviors.receiveMessage[Response] {
          case GetEventResponse(maybeEvent) =>
            println("getEventResponse: " + maybeEvent.get.venue)
            val availableTickets = maybeEvent.get.maxTickets
              if (numOfTickets > availableTickets) {
                val ticketStatus = "Unsuccessful"
                Effect
                  .persist(TicketPurchased(TicketsState(id, eventId, numOfTickets, ticketStatus, customerID)))
                  .thenReply(inventory)(newState => PurchaseResponse(newState))
              } else {
                eventManagerUpdate ! UpdateEvent(eventId, Some(10.3), Some("venue"), responseHandler)
                val ticketStatus = "Successful"
                Effect
                  .persist(TicketPurchased(TicketsState(id, eventId, numOfTickets, ticketStatus, customerID)))
                  .thenReply(inventory)(newState => PurchaseResponse(newState))
              }
            Behaviors.same
          case EventUpdatedResponse(maybeEvent) =>
            Behaviors.same
        }, "replyHandler")
        eventManager ! GetEvent(eventId, responseHandler)
        Effect.none



      case CancelTicket(_, inventory) =>
        val ticket_curr_status = state.ticketStatus
        ticket_curr_status match {
          case "Successful" =>
            Effect
              .persist(TicketCancelled("Cancelled"))
              .thenReply(inventory)(newState => CancellationResponse(newState))
          case "Unsuccessful" =>
            Effect
              .persist(TicketCancelled("Unsuccessful"))
              .thenReply(inventory)(newState => CancellationResponse(newState))
          case "Cancelled" =>
            Effect
              .persist(TicketCancelled("Cancelled"))
              .thenReply(inventory)(newState => CancellationResponse(newState))
        }
    }
  }

  val eventHandler: (TicketsState, TicketSellerEvent) => TicketsState = (state, event) =>
    event match {
      case TicketPurchased(ticketsState) =>
        ticketsState
      case TicketCancelled(change_status) =>
        state.copy(ticketStatus = change_status)
    }

  // ticketID and eventID is passed from customer.
  def apply(ticketID: String): Behavior[TicketSellerCommand] = Behaviors.setup {
    context => {

      // EVENTSOURCEDBEHAVIOR - IDEA PERCEIVED FROM ROCK THE JVM
      EventSourcedBehavior[TicketSellerCommand, TicketSellerEvent, TicketsState](
        persistenceId = PersistenceId.ofUniqueId(ticketID),
        emptyState = TicketsState(ticketID, "", 0, "", ""),
        commandHandler = commandHandler(context),
        eventHandler = eventHandler
      )
    }
  }
}

object InventoryManager {

  import TicketSellerActor.TicketSellerCommand
  import TicketSellerActor.TicketSellerCommand._
  import TicketSellerActor.TicketSellerCommand._

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
        val newPurchase = context.spawn(TicketSellerActor(id), id)
        Effect
          .persist(TicketsCreated(id))
          .thenReply(newPurchase)(_ => buyCommand)
      case cancelCommand@CancelTicket(ticketID, replyToInventory) =>
        state.tickets.get(ticketID) match {
          case Some(ticketActor) =>
            Effect.reply(ticketActor)(cancelCommand)
        }
    }
  }

  def eventHandler(context: ActorContext[TicketSellerCommand]): (State, Event) => State = (state, event) => {
    event match {
      case TicketsCreated(id) =>
        val tickets = context.child(id)
          .getOrElse(context.spawn(TicketSellerActor(id), id))
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
  import TicketSellerActor.TicketSellerCommand._
  import TicketSellerActor.TicketSellerResponse._
  import TicketSellerActor.TicketSellerResponse
  import TicketSellerActor.TicketSellerCommand._


  def main(args: Array[String]): Unit = {
    val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context =>

      val inventory = context.spawn(InventoryManager(), "InventoryActor")
//      val logger = context.log

      val responseHandlerInventory = context.spawn(Behaviors.receiveMessage[TicketSellerResponse]{
        case PurchaseResponse(ticket) =>
          val ticketStatus = ticket.ticketStatus
          println(ticketStatus)
          Behaviors.same
        case CancellationResponse(ticket) =>
          println("purchased customer: " + ticket.customerID)
          println("new ticket status: " + ticket.ticketStatus)
          Behaviors.same
      }, "replyHandler")

//      val logger = context.log


      //       test 1
      inventory ! BuyTicket("eventID001", 10, "customerID1", responseHandlerInventory)
//      inventory ! CancelTicket("BookingID-89c1b68f-8563-4c31-8fdb-e78a44a1c3bd", responseHandlerInventory )
      Behaviors.empty
    }
    val system = ActorSystem(rootBehavior, "TicketSellerDemo")
  }
}

