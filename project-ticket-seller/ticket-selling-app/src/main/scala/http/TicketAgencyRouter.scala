package http

import actors.TicketActor.TicketSellerCommand
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Directives._
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import http.Event.EventCommand._
import http.Event.EventResponse._
import http.Event.{EventCommand, EventResponse}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

case class EventCreationRequest(eventName: String, venue: String, organizer: String, cost: Double, maxTickets: Int, dateTime: String, duration: Int) {
  def toCommand(replyTo: ActorRef[EventResponse]): EventCommand = CreateEvent(eventName: String, venue: String, organizer: String, cost: Double, maxTickets: Int, dateTime: String, duration: Int, replyTo)
}

case class EventUpdateRequest(ticketCount: Int) {
  def toCommand(id: String, replyTo: ActorRef[EventResponse]): EventCommand = UpdateEvent(id, ticketCount, replyTo)
}

case class FailureResponse(reason: String)

class TicketAgencyRouter(eventManager: ActorRef[EventCommand], ticketManager: ActorRef[TicketSellerCommand])(implicit system: ActorSystem[_]) {

  implicit val timeout: Timeout = Timeout(5.seconds)

  def createEventRequest(request: EventCreationRequest): Future[EventResponse] =
    eventManager.ask(replyTo => request.toCommand(replyTo))

  def getEvent(id: String): Future[EventResponse] =
    eventManager.ask(replyTo => GetEvent(id, replyTo))

  def updateEvent(id: String, request: EventUpdateRequest): Future[EventResponse] =
    eventManager.ask(replyTo => request.toCommand(id, replyTo))

  val routes =
    pathPrefix("event") {
      pathEndOrSingleSlash {
        post {
          // parse the payload
          entity(as[EventCreationRequest]) {
            request =>
              /*
              - convert the request into a command
              - send the command to the Event Manager
              - expect a reply

               */
              onSuccess(createEventRequest(request)) {
                case EventCreatedResponse(eventId) =>
                  // - send back an HTTP response
                  respondWithHeader(Location(s"/event/$eventId")) {
                    complete(StatusCodes.Created)
                  }
              }
          }
        }
      } ~
        path(Segment) {

          id =>
            get {
              /*
               -send command to the event manager
               - send the command to the event
               -expect a reply
                */
              onSuccess(getEvent(id)) {
                case GetEventResponse(Some(maybeEvent)) =>
                  complete(maybeEvent)
                case GetEventResponse(None) =>
                  complete(StatusCodes.NotFound, FailureResponse(s"Event $id is not a valid event. Request for another event"))
              }
            } ~
              put {
                /*
                 -Transform the request to a command
                 - send the command to the event
                 -expect a reply
                  */
                // Don't need this. as we are going to update the event through Ticket Manager.
                entity(as[EventUpdateRequest]) {
                  request =>
                    onSuccess(updateEvent(id, request)) {
                      case EventUpdatedResponse(Some(maybeEvent)) =>
                        complete(maybeEvent)
                      case EventUpdatedResponse(None) =>
                        complete(StatusCodes.NotFound, FailureResponse(s"Event $id is not a valid event"))
                    }
                }
              }
        }
    }
  //      pathPrefix("customer") {
  //        pathEndOrSingleSlash {
  //          post {
  //
  //          } ~
  //            get {
  //
  //            }
  //        }
  //      } ~
}

object Event {

  // Commands
  trait EventCommand

  object EventCommand {
    case class CreateEvent(
                            eventName: String,
                            venue: String,
                            organizer: String,
                            cost: Double,
                            maxTickets: Int,
                            dateTime: String,
                            duration: Int,
                            replyTo: ActorRef[EventResponse]
                          ) extends EventCommand

    case class UpdateEvent(
                            eventId: String,
                            maxTickets: Int,
                            replyTo: ActorRef[EventResponse]
                          ) extends EventCommand

    case class GetEvent(eventId: String, replyTo: ActorRef[EventResponse]) extends EventCommand

  }

  // Events
  sealed trait EventEvent

  case class EventCreated(event: EventDetails) extends EventEvent

  case class EventUpdated(maxTickets: Int) extends EventEvent

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
  trait EventResponse

  object EventResponse {
    case class EventCreatedResponse(eventId: String) extends EventResponse

    case class EventUpdatedResponse(maybeEvent: Option[EventDetails]) extends EventResponse

    case class GetEventResponse(maybeEvent: Option[EventDetails]) extends EventResponse

  }

  import EventCommand._
  import EventResponse._

  // Command handler
  val commandHandler: (EventDetails, EventCommand) => Effect[EventEvent, EventDetails] = (state, command) =>
    command match {
      case CreateEvent(eventName, venue, organizer, cost, maxTickets, dateTime, duration, replyTo) =>
        val eventId = state.eventId
        val eventDetails = EventDetails(eventId, eventName, venue, organizer, cost, maxTickets, dateTime, duration)
        Effect
          .persist(EventCreated(eventDetails))
          .thenReply(replyTo)(_ => EventCreatedResponse(eventId))

      case UpdateEvent(_, newMaxTickets, replyTo) =>
        Effect
          .persist(EventUpdated(newMaxTickets))
          .thenReply(replyTo)(_ => EventUpdatedResponse(Some(state)))

      case GetEvent(_, replyTo) =>
        Effect.reply(replyTo)(GetEventResponse(Some(state)))

    }

  // Event handler
  val eventHandler: (EventDetails, EventEvent) => EventDetails = (state, event) =>
    event match {
      case EventCreated(eventDetails) =>
        eventDetails
      case EventUpdated(newMaxTickets) =>
        state.copy(maxTickets = newMaxTickets)
    }

  // Behavior definition
  def apply(eventId: String): Behavior[EventCommand] =
    EventSourcedBehavior[EventCommand, EventEvent, EventDetails](
      persistenceId = PersistenceId.ofUniqueId(eventId),
      emptyState = EventDetails("", "", "", "", 0.0, 0, "", 0),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}

object EventManager {

  // commands = messages

  import Event.EventCommand._
  import Event.EventResponse._
  import Event.EventCommand
  import Event.EventResponse

  // command for Get All Events
  case class GetAllEvents(replyTo: ActorRef[EventResponse]) extends EventCommand

  // Response for Get All Events
  case class GetAllEventsResponse(allEvents: Option[List[String]]) extends EventResponse

  // events
  sealed trait Event

  case class EventCreated(eventID: String) extends Event

  // state
  case class State(events: Map[String, ActorRef[EventCommand]])

  // command handler
  def commandHandler(context: ActorContext[EventCommand]): (State, EventCommand) => Effect[Event, State] = (state, command) =>
    command match {
      case createCommand@CreateEvent(eventName, venue, organizer, cost, maxTickets, dateTime, duration, replyTo) =>
        val eventId = "EventID-" + UUID.randomUUID().toString
        val newEvent = context.spawn(Event(eventId), eventId)
        Effect
          .persist(EventCreated(eventId))
          .thenReply(newEvent)(_ => createCommand)
      case updateCmd@UpdateEvent(eventId, newMaxTickets, replyTo) =>
        state.events.get(eventId) match {
          case Some(event) =>
            Effect.reply(event)(updateCmd)
        }
      case getCmd@GetEvent(eventId, replyTo) =>
        state.events.get(eventId) match {
          case Some(event) =>
            Effect.reply(event)(getCmd)
          case None =>
            Effect.reply(replyTo)(GetEventResponse(None))
        }
      case getAllEvents@GetAllEvents(replyTo) =>
        val allEvents = state.events.keys.toList
        Effect.reply(replyTo)(GetAllEventsResponse(Some(allEvents)))
    }

  // event handler
  def eventHandler(context: ActorContext[EventCommand]): (State, Event) => State = (state, event) =>
    event match {
      case EventCreated(eventId) =>
        val eventActor = context.child(eventId)
          .getOrElse(context.spawn(Event(eventId), eventId))
          .asInstanceOf[ActorRef[EventCommand]]
        state.copy(state.events + (eventId -> eventActor))
    }

  // behavior
  def apply(): Behavior[EventCommand] = Behaviors.setup { context =>
    EventSourcedBehavior[EventCommand, Event, State](
      persistenceId = PersistenceId.ofUniqueId("event-management"),
      emptyState = State(Map()),
      commandHandler = commandHandler(context),
      eventHandler = eventHandler(context)
    )
  }
}

object EventManagementPlayground {

  import Event.EventCommand._
  import Event.EventResponse._
  import Event.EventResponse
  import EventManager.GetAllEventsResponse
  import EventManager.GetAllEvents

  def main(args: Array[String]): Unit = {
    val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context =>
      val eventManagement = context.spawn(EventManager(), "eventManagement")
      val logger = context.log

      val responseHandler = context.spawn(Behaviors.receiveMessage[EventResponse] {
        case EventCreatedResponse(eventId) =>
          logger.info(s"Successfully created event - $eventId")
          Behaviors.same
        case EventUpdatedResponse(maybeEvent) =>
          logger.info(s"Successfully Updated event - $maybeEvent")
          Behaviors.same
        case GetEventResponse(maybeEvent) =>
          logger.info(s"Event details: $maybeEvent")
          Behaviors.same
        case GetAllEventsResponse(allEvents) =>
          logger.info(s"All Events: $allEvents")
          Behaviors.same
      }, "replyHandler")

      // ask pattern
      import akka.actor.typed.scaladsl.AskPattern._
      import scala.concurrent.duration._
      implicit val timeout: Timeout = Timeout(2.seconds)
      implicit val scheduler: Scheduler = context.system.scheduler
      implicit val ec: ExecutionContext = context.executionContext

      //      eventManagement ! CreateEvent("Concert", "Arena A", "Music Corp", 50.0, 1000, "2023-12-15 19:00:00", 120, responseHandler)
      //      eventManagement ! CreateEvent("Concert123", "Arena B", "Music Corpasdasd", 75.0, 2000, "2023-12-15 18:00:00", 320, responseHandler)
      eventManagement ! UpdateEvent("EventID-c8e71781-9727-4329-8adf-3775267cbedf", -10, responseHandler)
      //
      eventManagement ! GetEvent("EventID-c8e71781-9727-4329-8adf-3775267cbedf", responseHandler)

      Behaviors.empty
    }

    val system = ActorSystem(rootBehavior, "EventManagementDemo")
  }
}