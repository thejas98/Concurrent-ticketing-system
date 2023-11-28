package actors


import actors.TicketSellerActor.TicketsState
import akka.NotUsed
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.Persistence
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import java.util.UUID
import scala.util.{Failure, Success, Try}

object TicketSellerActor {
  // Commands
  sealed trait Command

  object Command {
    case class BuyTickets(eventId: String, numOfTickets: Int, replyToCustomer: ActorRef[Response]) extends Command

    case class GetTickets(event: String, replyToCustomer: ActorRef[Response]) extends Command

    case class GetBookingReference()
  }

  // Events
  sealed trait Event

  case class TicketPurchased(ticketsState: TicketsState) extends Event


  // Responses
  sealed trait Response

  object Response {
    case class PurchaseResponse(tickets: TicketsState) extends Response
  }

  // State
  case class TicketsState(ticketID: String, numberOfTickets: Int, ticketStatus: String, customer: String)

  import Response._
  import actors.TicketSellerActor.Command._

  // Command Handler
  // create booking id in command handler rather than apply method
  def commandHandler(context: ActorContext[Command]): (TicketsState, Command) => Effect[Event, TicketsState] = (state, command) => {
    //    import eventManager.Response
    command match {
      case BuyTickets(eventId, numOfTickets, customer) =>

        // create id only here. coz apply will create id for everything.

        val id = state.ticketID
        var availableTickets = 0
        val responseHandler = context.spawn(Behaviors.receiveMessage[EventResponse] {
          case GetEventResponse(maybeEvent) =>
            availableTickets = maybeEvent.get.id
            Behaviors.same
        }, "replyHandler")
        val eventManager = context.spawn(InventoryManager(event), "checkAvailability")
        eventManager ! GetEvent(event, responseHandler)

        if (numOfTickets > availableTickets){
          val ticketStatus = "Failed"
          Effect
            .persist(TicketPurchased(TicketsState(id, numOfTickets, ticketStatus, customer.get.id))
            .thenReply(customer)(newState => PurchaseResponse(newState))
        } else {
          //create a command in Event Actor.
          eventManager ! UpdateTickets(event, numOfTickets)
          val ticketStatus = "Success"
          Effect
            .persist(TicketPurchased(TicketsState(id, numOfTickets, ticketStatus, customer.get.id)))
            .thenReply(customer)(newState => PurchaseResponse(newState))
        }
      case GetTickets(event, customer) => {
      }
    }
  }

  val eventHandler: (TicketsState, Event) => TicketsState = (state, event) =>
    event match {
      case TicketPurchased(ticketsState) =>
        ticketsState
    }

  // ticketID and eventID is passed from customer.
  def apply(ticketID: String): Behavior[Command] = Behaviors.setup {
    context => {

      EventSourcedBehavior[Command, Event, TicketsState](
        persistenceId = PersistenceId.ofUniqueId(ticketID),
        emptyState = TicketsState(ticketID, 0, "", ""),
        commandHandler = commandHandler(context),
        eventHandler = eventHandler
      )
    }
  }

}


  object InventoryManager {

    import TicketSellerActor.Command
    import TicketSellerActor.Command._
    import TicketSellerActor.Response._

    // events
    sealed trait Event
    case class TicketsCreated(id: String) extends Event

    // state
    case class State(tickets: Map[String, ActorRef[Command]])

    def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] = (state, command) => {
      command match {
        case buyCommand @ BuyTickets(_, _, _) =>
          val id = "BookingID-" + UUID.randomUUID().toString
          val newTickets = context.spawn(TicketSellerActor(id), id)
          Effect
            .persist(TicketsCreated(id))
            .thenReply(newTickets)(_ => buyCommand)
      }
    }

    def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event) => {
      event match {
        case TicketsCreated(id) =>
          val tickets = context.child(id)
          .getOrElse(context.spawn(TicketSellerActor(id), id))
          .asInstanceOf[ActorRef[Command]]
          state.copy(state.tickets + (id -> tickets))

      }
    }

    def apply(): Behavior[Command] = Behaviors.setup {
      context => {
        EventSourcedBehavior[Command, Event, State](
          persistenceId = PersistenceId.ofUniqueId("InventoryManager"),
          emptyState = State(Map()),
          commandHandler = commandHandler(context),
          eventHandler = eventHandler(context)
        )
      }
    }


  }