package com.csye7200.cts.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import scala.util.{Failure, Success, Try}

object Event {

  // Commands
  trait Command
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
                            maxTickets: Int,
                            replyTo: ActorRef[Response]
                          ) extends Command

    case class GetEvent(eventId: String, replyTo: ActorRef[Response]) extends Command

  }

  // Events
  sealed trait Event
  case class EventCreated(event: EventDetails) extends Event
  case class EventUpdated(maxTickets: Int) extends Event

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
  trait Response
  object Response {
    case class EventCreatedResponse(eventId: String) extends Response
    case class EventUpdatedResponse(maybeEvent: Option[EventDetails]) extends Response
    case class GetEventResponse(maybeEvent: Option[EventDetails]) extends Response

  }

  import Command._
  import Response._

  // Command handler
  val commandHandler: (EventDetails, Command) => Effect[Event, EventDetails] = (state, command) =>
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
  val eventHandler: (EventDetails, Event) => EventDetails = (state, event) =>
    event match {
      case EventCreated(eventDetails) =>
        eventDetails
      case EventUpdated(newMaxTickets) =>
        state.copy(maxTickets = newMaxTickets)
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
