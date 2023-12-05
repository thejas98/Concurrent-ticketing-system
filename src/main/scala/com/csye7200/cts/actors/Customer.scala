package com.csye7200.cts.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

object Customer {

  // Commands
  trait CustomerCommand
  object CustomerCommand {
    case class CreateCustomer(firstName: String, lastName: String, email: String, phoneNumber: String, replyTo: ActorRef[CustomerResponse]) extends CustomerCommand
    case class GetCustomer(customerId:String ,replyTo: ActorRef[CustomerResponse]) extends CustomerCommand
  }

  // Events
  sealed trait CustomerEvent
  case class CustomerCreated(customer:CustomerDetails) extends CustomerEvent

  // State
  case class CustomerDetails(
                              customerId: String,
                              firstName: String,
                              lastName: String,
                              email: String,
                              phoneNumber: String
                            )

  // Responses
  trait CustomerResponse
  object CustomerResponse {
    case class CustomerCreatedResponse(customerId: String) extends CustomerResponse
    case class GetCustomerResponse(maybeCustomer: Option[CustomerDetails]) extends CustomerResponse
  }

  import CustomerCommand._
  import CustomerResponse._

  // Command handler
  val commandHandler: (CustomerDetails, CustomerCommand) => Effect[CustomerEvent, CustomerDetails] = (state, command) =>
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
  val eventHandler: (CustomerDetails, CustomerEvent) => CustomerDetails = (state, event) =>
    event match {
      case CustomerCreated(customerDetails) =>
        customerDetails

    }

  // Behavior definition
  def apply(customerID: String): Behavior[CustomerCommand] =
    EventSourcedBehavior[CustomerCommand, CustomerEvent, CustomerDetails](
      persistenceId = PersistenceId.ofUniqueId(customerID),
      emptyState = CustomerDetails(customerID, "", "", "", ""),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}

