package com.csye7200.cts.actors

import akka.NotUsed
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Scheduler,ActorSystem}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.Failure

object EventManager {

  // commands = messages
  import Event.EventCommand._
  import Event.EventResponse._
  import Event.EventCommand
  import Event.EventResponse

  // command for Get All Events
  case class GetAllEvents(replyTo : ActorRef[EventResponse] ) extends EventCommand

  // Response for Get All Events
  case class GetAllEventsResponse(allEvents: Option[List[String]]) extends EventResponse

  // events
  sealed trait Event
  case class EventCreated(eventID: String) extends Event
  case class EventUpdated(eventID: String) extends Event

  // state
  case class State(events: Map[String, ActorRef[EventCommand]])

  // command handler
  def commandHandler(context: ActorContext[EventCommand]): (State,EventCommand) => Effect[Event, State] = (state, command) =>
    command match {
      case createCommand @ CreateEvent(eventName, venue, organizer, cost, maxTickets, dateTime, duration, replyTo) =>
        val eventId = "EventID-"+UUID.randomUUID().toString
        val newEvent = context.spawn(Event(eventId), eventId)
        Effect
          .persist(EventCreated(eventId))
          .thenReply(newEvent)(_ => createCommand)
      case updateCmd @ UpdateEvent(eventId, newMaxTickets, replyTo) =>
        state.events.get(eventId) match {
          case Some(event) =>
            Effect
              .reply(event)(updateCmd)
        }
      case getCmd @ GetEvent(eventId, replyTo) =>
        state.events.get(eventId) match {
          case Some(event) =>
            println("event: " + event)
            Effect.reply(event)(getCmd)
          case None =>
            Effect.reply(replyTo)(GetEventResponse(None))
        }
      case getAllEvents @ GetAllEvents(replyTo) =>
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
//      case EventUpdated(eventId) =>
//        val eventActor = context.child(eventId)
//          .asInstanceOf[ActorRef[EventCommand]]
//        state.copy(state.events + (eventId -> eventActor))
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

