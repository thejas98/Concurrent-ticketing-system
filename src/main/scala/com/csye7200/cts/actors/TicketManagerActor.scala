package com.csye7200.cts.actors


import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout
import com.csye7200.cts.actors.Customer.CustomerCommand.GetCustomer
import com.csye7200.cts.actors.Customer.CustomerResponse.GetCustomerResponse
import com.csye7200.cts.actors.Event.EventCommand.GetEvent
import com.csye7200.cts.actors.Event.EventResponse.GetEventResponse
import com.csye7200.cts.actors.TicketActor.{TicketSellerCommand, TicketSellerResponse}
import com.csye7200.cts.actors.TicketActor.TicketSellerCommand._
import com.csye7200.cts.actors.TicketActor.TicketSellerResponse._

import java.util.UUID
import scala.concurrent.{Await, ExecutionContext}

object TicketManagerActor {

  // events
  sealed trait Event

  case class TicketsCreated(id: String) extends Event

  case class GetAllTicketsForCustomer(customerID: String, replyTo: ActorRef[TicketSellerResponse]) extends TicketSellerCommand
  case class GetAllTicketsForCustomerResponse(allTicketsForCustomer: List[String]) extends TicketSellerResponse

  // state
  case class State(tickets: Map[String, ActorRef[TicketSellerCommand]])

  def commandHandler(context: ActorContext[TicketSellerCommand]): (State, TicketSellerCommand) => Effect[Event, State] = (state, command) => {
    import scala.concurrent.duration._
    implicit val timeout: Timeout = Timeout(2.seconds)
    implicit val scheduler: Scheduler = context.system.scheduler
    implicit val ec: ExecutionContext = context.executionContext

    command match {
      case buyCommand@BuyTicket(eventManager, customerManager, eventId, numOfTickets, customerID, replyToTicketManager) =>
        val checkCustomer = customerManager ? (replyTo => GetCustomer(customerID, replyTo))
        val checkCustomerResult = Await.result(checkCustomer, timeout.duration)
        checkCustomerResult match {
          case GetCustomerResponse(maybeCustomer) =>
            maybeCustomer match {
              case Some(customer) =>
                val askGetEvent = eventManager ? (replyTo => GetEvent(eventId, replyTo))
                val getEventResult = Await.result(askGetEvent, timeout.duration)
                getEventResult match {
                  case GetEventResponse(maybeEvent) =>
                    maybeEvent match {
                      case Some(event) =>
                        val availableTickets = event.maxTickets
                        (numOfTickets < availableTickets, numOfTickets < 20) match {
                          case (true, true) =>
                            val id = "BookingID-" + UUID.randomUUID().toString
                            val newPurchase = context.spawn(TicketActor(id), id)
                            Effect
                              .persist(TicketsCreated(id))
                              .thenReply(newPurchase)(_ => buyCommand)
                          case _ =>
                            Effect
                              .reply(replyToTicketManager)(PurchaseResponse(None))
                        }
                      case None =>
                        Effect
                          .reply(replyToTicketManager)(NoEventResponse(None))
                    }
                }
              case None =>
                Effect.reply(replyToTicketManager)(NoCustomerResponse(None))
            }
        }

      case cancelCommand@CancelTicket(_, ticketID, replyToTicketManager) =>
        state.tickets.get(ticketID) match {
          case Some(ticketActor) =>
            Effect.reply(ticketActor)(cancelCommand)
          case None =>
            Effect.reply(replyToTicketManager)(GetTicketResponse(None))
        }

      case getCommand@GetTicket(ticketID, replyToCustomer) =>
        state.tickets.get(ticketID) match {
          case Some(ticketActor) =>
            Effect.reply(ticketActor)(getCommand)
          case None =>
            Effect.reply(replyToCustomer)(GetTicketResponse(None))
        }
      case GetAllTicketsForCustomer(customerID, replyToCustomer) =>
        val ticketIDs: List[String] = state.tickets.flatMap {
          case (key, ticketActor) =>
            val tickets = ticketActor ? (replyTo => GetTicket(key, replyTo))
            val ticketResult = Await.result(tickets, timeout.duration)
            ticketResult match {
              case GetTicketResponse(ticket) =>
                ticket match {
                  case Some(tickets) if tickets.customerID == customerID =>
                    Some(tickets.ticketID)
                }
            }
        }.toList
        Effect.reply(replyToCustomer)(GetAllTicketsForCustomerResponse(ticketIDs))
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
        persistenceId = PersistenceId.ofUniqueId("TicketManager"),
        emptyState = State(Map()),
        commandHandler = commandHandler(context),
        eventHandler = eventHandler(context)
      )
    }
  }
}