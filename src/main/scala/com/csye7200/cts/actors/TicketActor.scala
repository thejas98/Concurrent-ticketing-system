package com.csye7200.cts.actors

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout
import com.csye7200.cts.actors.Event.EventCommand
import com.csye7200.cts.actors.Event.EventCommand._
import com.csye7200.cts.actors.Event.EventResponse._

import scala.concurrent.{Await, ExecutionContext}

object TicketActor {
  // Commands
  sealed trait TicketSellerCommand

  object TicketSellerCommand {
    case class BuyTicket(eventManager: ActorRef[EventCommand], eventId: String, numOfTickets: Int, customerID: String, replyToTicketManager: ActorRef[TicketSellerResponse]) extends TicketSellerCommand

    case class CancelTicket(eventManager: ActorRef[EventCommand], ticketID: String, replyToTicketManager: ActorRef[TicketSellerResponse]) extends TicketSellerCommand

    case class GetTicket(ticketID: String, replyToTicketManager: ActorRef[TicketSellerResponse]) extends TicketSellerCommand
  }

  // Events
  sealed trait TicketSellerEvent

  case class TicketPurchased(ticketsState: TicketsState) extends TicketSellerEvent

  case class TicketCancelled(changeStatus: String) extends TicketSellerEvent

  // Responses
  sealed trait TicketSellerResponse

  object TicketSellerResponse {

    case class GetTicketResponse(maybeTicket: Option[TicketsState]) extends TicketSellerResponse

    case class PurchaseResponse(tickets: Option[TicketsState]) extends TicketSellerResponse

    case class CancellationResponse(tickets: Option[TicketsState]) extends TicketSellerResponse

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
    implicit val timeout: Timeout = Timeout(10.seconds)
    implicit val scheduler: Scheduler = context.system.scheduler
    implicit val ec: ExecutionContext = context.executionContext

    command match {

      case BuyTicket(eventManager, eventId, numOfTickets, customerID, ticketManager) =>
        val id = state.ticketID
        println("eventManager:" + eventManager)
        val askGetEvent = eventManager ? (replyTo => GetEvent(eventId, replyTo))
        val result = Await.result(askGetEvent, timeout.duration)
        result match {

          case GetEventResponse(maybeEvent) =>
            maybeEvent match {
              case Some(event) =>
                println("event available")
                val availableTickets = event.maxTickets
                if (numOfTickets > availableTickets || numOfTickets > 20) {
                  val ticketStatus = "Unsuccessful"
                  Effect
                    .persist(TicketPurchased(TicketsState(id, eventId, numOfTickets, ticketStatus, customerID)))
                    .thenReply(ticketManager)(newState => PurchaseResponse(Some(newState)))
                }
                else {
                  val minusTickets = -numOfTickets
                  val updateEventResponse = eventManager ? (replyTo => UpdateEvent(eventId, minusTickets, replyTo))
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
                  println()

                  val ticketStatus = "Successful"
                  Effect
                    .persist(TicketPurchased(TicketsState(id, eventId, numOfTickets, ticketStatus, customerID)))
                    .thenReply(ticketManager)(newState => PurchaseResponse(Some(newState)))
                }
              /* when event is not present */
              case None =>
                println("no such event")
                val ticketStatus = "Event-Unavailable"
                Effect
                  .persist(TicketPurchased(TicketsState(id, eventId, numOfTickets, ticketStatus, customerID)))
                  .thenReply(ticketManager)(newState => PurchaseResponse(Some(newState)))
            }
        }

      case GetTicket(_, ticketManager) =>
        Effect.reply(ticketManager)(GetTicketResponse(Some(state)))

      case CancelTicket(eventManager,_, ticketManager) =>
        val ticket_curr_status = state.ticketStatus
        ticket_curr_status match {

          case "Successful" =>
            eventManager.ask(replyTo => UpdateEvent(state.eventID, state.numberOfTickets, replyTo))
            Effect
              .persist(TicketCancelled("Cancelled"))
              .thenReply(ticketManager)(newState => CancellationResponse(Some(newState)))

          case "Unsuccessful" =>
            Effect
              .reply(ticketManager)(CancellationResponse(None))

          case "Cancelled" =>
            Effect
              .reply(ticketManager)(CancellationResponse(None))

          case "Event-Unavailable" =>
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