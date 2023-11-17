package com.csye7200.cts.actors

import akka.NotUsed
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout
import com.csye7200.cts.actors.PersistentEventManager.EventDetails

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.Failure

object EventManagement {

  // commands = messages
  import PersistentEventManager.Command._
  import PersistentEventManager.Response._
  import PersistentEventManager.Command


  // events
  sealed trait Event
  case class EventCreated(eventDetails: EventDetails) extends Event

  // state
  case class State(events: Map[String, ActorRef[Command]])

  // command handler
  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] = (state, command) =>
    command match {
      case createCommand @ CreateEvent(eventName, venue, organizer, cost, maxTickets, dateTime, duration, replyTo) =>
        val eventId = UUID.randomUUID().toString
        val newEvent = context.spawn(PersistentEventManager(eventId), eventId)
        Effect
          .persist(EventCreated(EventDetails(eventId, eventName, venue, organizer, cost, maxTickets, dateTime, duration)))
          .thenReply(newEvent)(_ => createCommand)
      case updateCmd @ UpdateEvent(eventId, newCost, newVenue, replyTo) =>
        state.events.get(eventId) match {
          case Some(event) =>
            Effect.reply(event)(updateCmd)
          case None =>
            Effect.reply(replyTo)(EventUpdatedResponse(Failure(new RuntimeException("Event cannot be found"))))
        }
      case getCmd @ GetEvent(eventId, replyTo) =>
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

object EventManagementPlayground {
  import PersistentEventManager.Command._
  import PersistentEventManager.Response._
  import PersistentEventManager.Response

  def main(args: Array[String]): Unit = {
    val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context =>
      val eventManagement = context.spawn(EventManagement(), "eventManagement")
      val logger = context.log

      val responseHandler = context.spawn(Behaviors.receiveMessage[Response] {
        case EventCreatedResponse(eventId) =>
          logger.info(s"Successfully created event $eventId")
          Behaviors.same
        case GetEventResponse(maybeEvent) =>
          logger.info(s"Event details: $maybeEvent")
          Behaviors.same
      }, "replyHandler")

      // ask pattern
      import akka.actor.typed.scaladsl.AskPattern._
      import scala.concurrent.duration._
      implicit val timeout: Timeout = Timeout(2.seconds)
      implicit val scheduler: Scheduler = context.system.scheduler
      implicit val ec: ExecutionContext = context.executionContext

      eventManagement ! CreateEvent("Concert", "Arena A", "Music Corp", 50.0, 1000, "2023-12-15 19:00:00", 120, responseHandler)
      eventManagement ! GetEvent("423d805e-0e70-4a58-9475-544efb2437ff", responseHandler)

      Behaviors.empty
    }

    val system = ActorSystem(rootBehavior, "EventManagementDemo")
  }
}
