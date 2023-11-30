package com.csye7200.cts.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}


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
          .thenReply(replyTo)(newState => EventUpdatedResponse(Some(newState)))

      case GetEvent(_, replyTo) =>
        Effect.reply(replyTo)(GetEventResponse(Some(state)))

    }

  // Event handler
  val eventHandler: (EventDetails, EventEvent) => EventDetails = (state, event) =>
    event match {
      case EventCreated(eventDetails) =>
        eventDetails
      case EventUpdated(newMaxTickets) =>
        state.copy(maxTickets = state.maxTickets + newMaxTickets)
    }

  // Behavior definition
  def apply(eventId: String): Behavior[EventCommand] =
    EventSourcedBehavior[EventCommand, EventEvent, EventDetails](
      persistenceId = PersistenceId.ofUniqueId(eventId),
      emptyState = EventDetails(eventId, "", "", "", 0.0, 0, "", 0),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}
