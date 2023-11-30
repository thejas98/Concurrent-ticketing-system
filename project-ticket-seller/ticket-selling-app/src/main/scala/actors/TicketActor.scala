package actors

import actors.Event.EventCommand._
import actors.TicketActor.TicketSellerResponse.NoSuchTicketResponse
import actors.TicketActor.TicketsState
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
import scala.util.{Failure, Success, Try}

object TicketActor {
  // Commands
  sealed trait TicketSellerCommand

  object TicketSellerCommand {
    case class BuyTicket(eventId: String, numOfTickets: Int, customerID: String, replyToTicketManager: ActorRef[TicketSellerResponse]) extends TicketSellerCommand

    case class CancelTicket(ticketID: String, replyToTicketManager: ActorRef[TicketSellerResponse]) extends TicketSellerCommand

    case class GetBookingReference()
  }

  // Events
  sealed trait TicketSellerEvent

  case class TicketPurchased(ticketsState: TicketsState) extends TicketSellerEvent

  case class TicketCancelled(changeStatus: String) extends TicketSellerEvent

  // Responses
  sealed trait TicketSellerResponse

  object TicketSellerResponse {
    case class PurchaseResponse(tickets: Option[TicketsState]) extends TicketSellerResponse

    case class CancellationResponse(tickets: Option[TicketsState]) extends TicketSellerResponse

    case class NoSuchTicketResponse(tickets: Option[TicketsState]) extends TicketSellerResponse
  }

  // State
  case class TicketsState(ticketID: String, eventID: String, numberOfTickets: Int, ticketStatus: String, customerID: String)

  // available tickets
  case class AvailableTickets(value: Int)

  import TicketActor.TicketSellerResponse._
  import actors.TicketActor.TicketSellerCommand._

  // Command Handler
  // create booking id in command handler rather than apply method
  def commandHandler(context: ActorContext[TicketSellerCommand]): (TicketsState, TicketSellerCommand) => Effect[TicketSellerEvent, TicketsState] = (state, command) => {

    import Event.EventCommand._
    import Event.EventResponse
    import Event.EventResponse._
    import scala.concurrent.duration._
    implicit val timeout: Timeout = Timeout(2.seconds)
    implicit val scheduler: Scheduler = context.system.scheduler
    implicit val ec: ExecutionContext = context.executionContext

    command match {
      case BuyTicket(eventId, numOfTickets, customerID, inventory) =>
        val id = state.ticketID
        val eventManager = context.spawn(EventManager(), "checkAvailability")
        val askGetEvent = eventManager ? (replyTo => GetEvent(eventId, replyTo))
        val result = Await.result(askGetEvent, timeout.duration)
        result match {
          case GetEventResponse(maybeEvent) =>
            println("getEventResponse during availability checking: " + maybeEvent.get.venue)
            val availableTickets = maybeEvent.get.maxTickets
            if (numOfTickets > availableTickets) {
              println(availableTickets)
              val ticketStatus = "Unsuccessful"
              Effect
                .persist(TicketPurchased(TicketsState(id, eventId, numOfTickets, ticketStatus, customerID)))
                .thenReply(inventory)(newState => PurchaseResponse(Some(newState)))
            }
            else {
              val minusTickets = -numOfTickets
              val eventManagerUpdate = context.spawn(EventManager(), "updateAvailability")
              val updateEventResponse = eventManagerUpdate.ask(replyTo => UpdateEvent(eventId, minusTickets, replyTo))
              updateEventResponse.map {
                case EventUpdatedResponse(maybeEvent) =>
                  maybeEvent.foreach {
                    event =>
                      println(event.eventId)
                      println(event.eventName)
                      println(event.maxTickets)
                      println(event.venue)
                  }
              }
              println("inside else condition")
              val ticketStatus = "Successful"
              Effect
                .persist(TicketPurchased(TicketsState(id, eventId, numOfTickets, ticketStatus, customerID)))
                .thenReply(inventory)(newState => PurchaseResponse(Some(newState)))
            }
          /* when await exceeds the time limit it won't return a value */
          case GetEventResponse(None) => Effect.reply(inventory)(PurchaseResponse(None))
        }

      case CancelTicket(ticketID, inventory) =>
        val ticket_curr_status = state.ticketStatus
        ticket_curr_status match {
          case "Successful" =>
            val eventManagerUpdate = context.spawn(EventManager(), "updateAvailability")
            eventManagerUpdate.ask(replyTo => UpdateEvent(state.eventID, state.numberOfTickets, replyTo))
            Effect
              .persist(TicketCancelled("Cancelled"))
              .thenReply(inventory)(newState => CancellationResponse(Some(newState)))
          case "Unsuccessful" =>
            Effect
              .reply(inventory)(CancellationResponse(None))
          case "Cancelled" =>
            Effect
              .reply(inventory)(CancellationResponse(None))
        }
    }
  }

  def eventHandler(context: ActorContext[TicketSellerCommand]): (TicketsState, TicketSellerEvent) => TicketsState = (state, event) => {

    event match {
      case TicketPurchased(ticketsState) =>
        println("event Handler")
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