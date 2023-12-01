package com.csye7200.cts.actors

import TicketActor.TicketSellerResponse._
import akka.NotUsed
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import java.util.UUID

object TicketManagerActor {

  import TicketActor.TicketSellerCommand
  import TicketActor.TicketSellerCommand._



  // events
  sealed trait Event

  case class TicketsCreated(id: String) extends Event

  // state
  case class State(tickets: Map[String, ActorRef[TicketSellerCommand]])

  def commandHandler(context: ActorContext[TicketSellerCommand]): (State, TicketSellerCommand) => Effect[Event, State] = (state, command) => {
    println("state: " + state.tickets)
    command match {
      case buyCommand @ BuyTicket(_, _, _, _) =>
        val id = "BookingID-" + UUID.randomUUID().toString
        val newPurchase = context.spawn(TicketActor(id), id)
        Effect
          .persist(TicketsCreated(id))
          .thenReply(newPurchase)(_ => buyCommand)

      case cancelCommand @ CancelTicket(ticketID, replyToTicketManager) =>
        state.tickets.get(ticketID) match {
          case Some(ticketActor) =>
            Effect.reply(ticketActor)(cancelCommand)
          case None =>
            Effect.reply(replyToTicketManager)(GetTicketResponse(None))
        }

      case getCommand @ GetTicket(ticketID, replyToCustomer) =>
        state.tickets.get(ticketID) match {
          case Some(ticketActor) =>
            Effect.reply(ticketActor)(getCommand)
          case None =>
            Effect.reply(replyToCustomer)(GetTicketResponse(None))
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

