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
            Effect.reply(event)(updateCmd)
        }
      case getCmd @ GetEvent(eventId, replyTo) =>
        state.events.get(eventId) match {
          case Some(event) =>
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
          logger.info(s"Successfully created event $eventId")
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
      eventManagement ! CreateEvent("Concert123", "Arena B", "Music Corpasdasd", 75.0, 2000, "2023-12-15 18:00:00", 320, responseHandler)


      eventManagement ! GetAllEvents(responseHandler)

      Behaviors.empty
    }

    val system = ActorSystem(rootBehavior, "EventManagementDemo")
  }
}
