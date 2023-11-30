package com.csye7200.cts.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

object Customer {

  // Commands
  trait Command
  object Command {
    case class CreateCustomer(firstName: String, lastName: String, email: String, phoneNumber: String, replyTo: ActorRef[Response]) extends Command
    case class GetCustomer(customerId:String ,replyTo: ActorRef[Response]) extends Command
  }

  // Events
  sealed trait Event
  case class CustomerCreated(customer:CustomerDetails) extends Event

  // State
  case class CustomerDetails(
                              customerId: String,
                              firstName: String,
                              lastName: String,
                              email: String,
                              phoneNumber: String
                            )

  // Responses
  trait Response
  object Response {
    case class CustomerCreatedResponse(customerId: String) extends Response
    case class GetCustomerResponse(maybeCustomer: Option[CustomerDetails]) extends Response
  }

  import Command._
  import Response._

  // Command handler
  val commandHandler: (CustomerDetails, Command) => Effect[Event, CustomerDetails] = (state, command) =>
    command match {
      case CreateCustomer(firstName, lastName, email, phoneNumber, replyTo) =>
        val customerId = state.customerId
        val customerDetails = CustomerDetails(customerId, firstName, lastName, email, phoneNumber)
        Effect
          .persist(CustomerCreated(customerDetails))
          .thenReply(replyTo)(_ => CustomerCreatedResponse(customerId))

      case GetCustomer(_, replyTo) =>
        Effect.reply(replyTo)(GetCustomerResponse(Some(state)))

    }

  // Event handler
  val eventHandler: (CustomerDetails, Event) => CustomerDetails = (state, event) =>
    event match {
      case CustomerCreated(customerDetails) =>
        customerDetails

    }

  // Behavior definition
  def apply(customerID: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, CustomerDetails](
      persistenceId = PersistenceId.ofUniqueId(customerID),
      emptyState = CustomerDetails("", "", "", "", ""),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}

