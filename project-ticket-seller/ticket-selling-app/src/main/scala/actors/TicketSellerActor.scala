package actors

import actors.PersistentEventManager.Command.UpdateEvent
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

  import TicketActor.TicketSellerResponse._
  import actors.TicketActor.TicketSellerCommand._

  // Command Handler
  // create booking id in command handler rather than apply method
  def commandHandler(context: ActorContext[TicketSellerCommand]): (TicketsState, TicketSellerCommand) => Effect[TicketSellerEvent, TicketsState] = (state, command) => {

    import PersistentEventManager.Response
    import PersistentEventManager.Response._
    import PersistentEventManager.Command._
    import scala.concurrent.duration._
    implicit val timeout: Timeout = Timeout(10.seconds)
    implicit val scheduler: Scheduler = context.system.scheduler
    implicit val ec: ExecutionContext = context.executionContext

    command match {
      case BuyTicket(eventId, numOfTickets, customerID, inventory) =>
        val id = state.ticketID




        val eventManager = context.spawn(EventManagement(), "checkAvailability")
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
                .thenReply(inventory)(newState => PurchaseResponse(newState))
            }
            else {
              val eventManagerUpdate = context.spawn(EventManagement(), "updateAvailability")
              val updateEventResponse = eventManagerUpdate.ask(replyTo => UpdateEvent(eventId, Some(10.3), Some("venue new thing34"), replyTo))
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
                .thenReply(inventory)(newState => PurchaseResponse(newState))
            }
          /* when await exceeds the time limit it won't return a value */
          case _ => Effect.none
        }

      case CancelTicket(ticketID, inventory) =>
        val ticket_curr_status = state.ticketStatus
        ticket_curr_status match {
          case "Successful" =>
            val eventManagerUpdate = context.spawn(EventManagement(), "updateAvailability")
            val updateEventResponse = eventManagerUpdate.ask(replyTo => UpdateEvent(state.eventID, Some(10.3), Some("venue new thing34"), replyTo)).map()

            Effect
              .persist(TicketCancelled("Cancelled"))
              .thenReply(inventory)(newState => CancellationResponse(newState))
          case "Unsuccessful" =>
            Effect
              .reply(inventory)(CancellationResponse(state))
          case "Cancelled" =>
            Effect
              .reply(inventory)(CancellationResponse(state))
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

object InventoryManager {

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
  import PersistentEventManager.Command._
  import PersistentEventManager.Response
  import PersistentEventManager.Response._

  def main(args: Array[String]): Unit = {
    val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context =>
      val inventory = context.spawn(InventoryManager(), "InventoryActor")
      val responseHandlerInventory = context.spawn(Behaviors.receiveMessage[TicketSellerResponse] {
        case PurchaseResponse(ticket) =>
          Behaviors.same
        case CancellationResponse(ticket) =>
          Behaviors.same
      }, "replyHandlerInventory")

      val responseHandler = context.spawn(Behaviors.receiveMessage[Response] {
        case EventCreatedResponse(eventId) =>
          println("event ID:" + eventId)
          Behaviors.same
        case GetEventResponse(maybeEvent) =>
          println("Event Max Tickets: " + maybeEvent.get.maxTickets)
          Behaviors.same
      }, "replyHandler")


      // Testing eventmanagement
//      eventManagement ! CreateEvent("Celtics Match", "Arena A", "Music Corp", 50.0, 1000, "2023-12-15 19:00:00", 120, responseHandler)
//      eventManagement ! GetEvent("88cbb056-ad94-4de9-86aa-56d767907c08", responseHandler)

      //       test 1
      inventory ! BuyTicket("acb4e2d6-877f-4097-a644-27f53920a6a1", 100, "Michael Jordan Testing23233234", responseHandlerInventory)
      inventory ! CancelTicket("BookingID-54844367-c4b7-441b-9b42-c9daadf76452", responseHandlerInventory)
      Behaviors.empty
    }
    val system = ActorSystem(rootBehavior, "TicketSellerDemo")
  }
}

object EventManagement {

  // commands = messages

  import PersistentEventManager.Command._
  import PersistentEventManager.Response._
  import PersistentEventManager.Command

  import PersistentEventManager.EventDetails

  // events
  sealed trait Event

  case class EventCreated(eventDetails: EventDetails) extends Event

  // state
  case class State(events: Map[String, ActorRef[Command]])

  // command handler
  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] = (state, command) =>

    command match {
      case createCommand@CreateEvent(eventName, venue, organizer, cost, maxTickets, dateTime, duration, replyTo) =>
        println("state: " + state)
        val eventId = UUID.randomUUID().toString
        val newEvent = context.spawn(PersistentEventManager(eventId), eventId)
        Effect
          .persist(EventCreated(EventDetails(eventId, eventName, venue, organizer, cost, maxTickets, dateTime, duration)))
          .thenReply(newEvent)(_ => createCommand)
      case updateCmd@UpdateEvent(eventId, newCost, newVenue, replyTo) =>
        state.events.get(eventId) match {
          case Some(event) =>
            Effect.reply(event)(updateCmd)
          case None =>
            Effect.reply(replyTo)(EventUpdatedResponse(Failure(new RuntimeException("Event cannot be found"))))
        }
      case getCmd@GetEvent(eventId, replyTo) =>
        state.events.get(eventId) match {
          case Some(event) =>
            Effect.reply(event)(getCmd)
          case None =>
            Effect.reply(replyTo)(GetEventResponse(None))
        }
    }

  // event handler
  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event) =>
    event match {
      case EventCreated(eventDetails) =>
        val eventActor = context.child(eventDetails.eventId)
          .getOrElse(context.spawn(PersistentEventManager(eventDetails.eventId), eventDetails.eventId))
          .asInstanceOf[ActorRef[Command]]
        state.copy(state.events + (eventDetails.eventId -> eventActor))
    }

  // behavior
  def apply(): Behavior[Command] = Behaviors.setup { context =>
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId("event-management"),
      emptyState = State(Map()),
      commandHandler = commandHandler(context),
      eventHandler = eventHandler(context)
    )
  }
}

object PersistentEventManager {

  // Commands
  sealed trait Command

  object Command {
    case class CreateEvent(
                            eventName: String,
                            venue: String,
                            organizer: String,
                            cost: Double,
                            maxTickets: Int,
                            dateTime: String,
                            duration: Int,
                            replyTo: ActorRef[Response]
                          ) extends Command

    case class UpdateEvent(
                            eventId: String,
                            cost: Option[Double],
                            venue: Option[String],
                            replyTo: ActorRef[Response]
                          ) extends Command

    case class GetEvent(eventId: String, replyTo: ActorRef[Response]) extends Command
  }

  // Events
  sealed trait Event

  case class EventCreated(event: EventDetails) extends Event

  case class EventUpdated(cost: Option[Double], venue: Option[String]) extends Event

  // State
  case class EventDetails(
                           eventId: String,
                           eventName: String,
                           venue: String,
                           organizer: String,
                           cost: Double,
                           maxTickets: Int,
                           dateTime: String,
                           duration: Int
                         )

  // Responses
  sealed trait Response

  object Response {
    case class EventCreatedResponse(eventId: String) extends Response

    case class EventUpdatedResponse(maybeEvent: Try[EventDetails]) extends Response

    case class GetEventResponse(maybeEvent: Option[EventDetails]) extends Response
  }

  import Command._
  import Response._

  // Command handler
  val commandHandler: (EventDetails, Command) => Effect[Event, EventDetails] = (state, command) =>
    command match {
      case CreateEvent(eventName, venue, organizer, cost, maxTickets, dateTime, duration, replyTo) =>
        val eventId = java.util.UUID.randomUUID().toString
        val eventDetails = EventDetails(eventId, eventName, venue, organizer, cost, maxTickets, dateTime, duration)
        Effect
          .persist(EventCreated(eventDetails))
          .thenReply(replyTo)(_ => EventCreatedResponse(eventId))

      case UpdateEvent(eventId, newCost, newVenue, replyTo) =>
        Effect
          .persist(EventUpdated(newCost, newVenue))
          .thenReply(replyTo)(_ => EventUpdatedResponse(Success(state.copy(cost = newCost.getOrElse(state.cost), venue = newVenue.getOrElse(state.venue)))))

      case GetEvent(_, replyTo) =>
        Effect.reply(replyTo)(GetEventResponse(Some(state)))
    }

  // Event handler
  val eventHandler: (EventDetails, Event) => EventDetails = (state, event) =>
    event match {
      case EventCreated(eventDetails) =>
        eventDetails
      case EventUpdated(newCost, newVenue) =>
        state.copy(cost = newCost.getOrElse(state.cost), venue = newVenue.getOrElse(state.venue))
    }

  // Behavior definition
  def apply(eventId: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, EventDetails](
      persistenceId = PersistenceId.ofUniqueId(eventId),
      emptyState = EventDetails("", "", "", "", 0.0, 0, "", 0),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}

