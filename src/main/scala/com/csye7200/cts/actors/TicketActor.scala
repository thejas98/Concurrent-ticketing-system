package com.csye7200.cts.actors

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout
import com.csye7200.cts.actors.Customer.CustomerCommand
import com.csye7200.cts.actors.Customer.CustomerCommand.GetCustomer
import com.csye7200.cts.actors.Customer.CustomerResponse.GetCustomerResponse
import com.csye7200.cts.actors.Event.{EventCommand, EventResponse}
import com.csye7200.cts.actors.Event.EventCommand._
import com.csye7200.cts.actors.Event.EventResponse._

import scala.concurrent.{Await, ExecutionContext}

object TicketActor {
  // Commands
  trait TicketSellerCommand

  object TicketSellerCommand {
    case class BuyTicket(eventManager: ActorRef[EventCommand], customerManager: ActorRef[CustomerCommand], eventId: String, numOfTickets: Int, customerID: String, replyToTicketManager: ActorRef[TicketSellerResponse]) extends TicketSellerCommand

    case class CancelTicket(eventManager: ActorRef[EventCommand], ticketID: String, replyToTicketManager: ActorRef[TicketSellerResponse]) extends TicketSellerCommand

    case class GetTicket(ticketID: String, replyToTicketManager: ActorRef[TicketSellerResponse]) extends TicketSellerCommand
  }

  // Events
  sealed trait TicketSellerEvent

  case class TicketPurchased(ticketsState: TicketsState) extends TicketSellerEvent

  case class TicketCancelled(changeStatus: String) extends TicketSellerEvent

  // Responses
  trait TicketSellerResponse

  object TicketSellerResponse {

    case class GetTicketResponse(maybeTicket: Option[TicketsState]) extends TicketSellerResponse

    case class PurchaseResponse(tickets: Option[TicketsState]) extends TicketSellerResponse

    case class CancellationResponse(tickets: Option[TicketsState]) extends TicketSellerResponse

    case class NoEventResponse(tickets: Option[TicketsState]) extends TicketSellerResponse

    case class NoCustomerResponse(tickets: Option[TicketsState]) extends TicketSellerResponse


  }

  // State
  case class TicketsState(ticketID: String, eventID: String, numberOfTickets: Int, ticketStatus: String, customerID: String)

  // available tickets
  case class AvailableTickets(value: Int)

  import TicketActor.TicketSellerCommand._
  import TicketActor.TicketSellerResponse._

  // Command Handler
  // create booking id in command handler rather than apply method

  def commandHandler(context: ActorContext[TicketSellerCommand]): (TicketsState, TicketSellerCommand) => Effect[TicketSellerEvent, TicketsState] = (state, command) => {

    import scala.concurrent.duration._
    implicit val timeout: Timeout = Timeout(2.seconds)
    implicit val scheduler: Scheduler = context.system.scheduler
    implicit val ec: ExecutionContext = context.executionContext

    command match {

      case BuyTicket(eventManager, customerManager, eventId, numOfTickets, customerID, ticketManager) =>
        val id = state.ticketID
        val responseHandler = context.spawn(Behaviors.receiveMessage[EventResponse]
          {
            case EventUpdatedResponse(maybeEvent) =>
              Behaviors.same
          },"replyHandler")
        val minusTickets = -numOfTickets
//        val updateEventResponse = eventManager ? (replyTo => UpdateEvent(eventId, minusTickets, replyTo))
        eventManager ! UpdateEvent(eventId, minusTickets, responseHandler)
        val ticketStatus = "Successful"
        Effect
          .persist(TicketPurchased(TicketsState(id, eventId, numOfTickets, ticketStatus, customerID)))
          .thenReply(ticketManager)(newState => PurchaseResponse(Some(newState)))

      case GetTicket(_, ticketManager) =>
        Effect.reply(ticketManager)(GetTicketResponse(Some(state)))

      case CancelTicket(eventManager, _, ticketManager) =>
        val ticket_curr_status = state.ticketStatus
        ticket_curr_status match {
          case "Successful" =>
            eventManager.ask(replyTo => UpdateEvent(state.eventID, state.numberOfTickets, replyTo))
            Effect
              .persist(TicketCancelled("Cancelled"))
              .thenReply(ticketManager)(newState => CancellationResponse(Some(newState)))
          case "Cancelled" =>
            Effect
              .reply(ticketManager)(CancellationResponse(None))

        }
    }
  }

  def eventHandler(context: ActorContext[TicketSellerCommand]): (TicketsState, TicketSellerEvent) => TicketsState = (state, event) => {

    event match {

      case TicketPurchased(ticketsState) =>
        ticketsState

      case TicketCancelled(change_status) =>
        state.copy(ticketStatus = change_status)
    }
  }

  // ticketID and eventID is passed from customer.
  def apply(ticketID: String): Behavior[TicketSellerCommand] = Behaviors.setup {
    context => {

      // EVENTSOURCEDBEHAVIOR - IDEA PERCEIVED FROM ROCK THE JVM
      EventSourcedBehavior[TicketSellerCommand, TicketSellerEvent, TicketsState](
        persistenceId = PersistenceId.ofUniqueId(ticketID),
        emptyState = TicketsState(ticketID, "", 0, "", ""),
        commandHandler = commandHandler(context),
        eventHandler = eventHandler(context)
      )
    }
  }
}