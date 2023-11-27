package com.csye7200.cts.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import scala.util.{Failure, Success, Try}

object PersistentCustomer {

  // Commands
  sealed trait Command
  object Command {
    case class CreateCustomer(
                               firstName: String,
                               lastName: String,
                               email: String,
                               phoneNumber: String,
                               replyTo: ActorRef[Response]
                             ) extends Command

    case class UpdateCustomer(
                               customerId: String,
                               firstName: String,
                               lastName: String,
                               email: String,
                               phoneNumber: String,
                               replyTo: ActorRef[Response]
                             ) extends Command

    case class GetCustomer(customerId: String, replyTo: ActorRef[Response]) extends Command
  }

  // Events
  sealed trait Event
  case class CustomerCreated(
                              customerId: String,
                              firstName: String,
                              lastName: String,
                              email: String,
                              phoneNumber: String
                            ) extends Event

  case class CustomerUpdated(
                              firstName: String,
                              lastName: String,
                              email: String,
                              phoneNumber: String
                            ) extends Event

  // State
  case class CustomerDetails(
                              customerId: String,
                              firstName: String,
                              lastName: String,
                              email: String,
                              phoneNumber: String
                            )

  // Responses
  sealed trait Response
  object Response {
    case class CustomerCreatedResponse(customerId: String) extends Response
    case class GetCustomerResponse(maybeCustomer: Option[CustomerDetails]) extends Response
    case class UpdateCustomerResponse(maybeUpdatedCustomer: Try[CustomerDetails]) extends Response
  }

  import Command._
  import Response._

  // Command handler
  val commandHandler: (CustomerDetails, Command) => Effect[Event, CustomerDetails] = (state, command) =>
    command match {
      case CreateCustomer(firstName, lastName, email, phoneNumber, replyTo) =>
        val customerId = java.util.UUID.randomUUID().toString
        val customerDetails = CustomerDetails(customerId, firstName, lastName, email, phoneNumber)
        Effect
          .persist(CustomerCreated(customerId, firstName, lastName, email, phoneNumber))
          .thenReply(replyTo)(_ => CustomerCreatedResponse(customerId))

      case UpdateCustomer(customerId, newFirstName, newLastName, newEmail, newPhoneNumber, replyTo) =>
        val updatedCustomer = state.copy(
          firstName = newFirstName,
          lastName = newLastName,
          email = newEmail,
          phoneNumber = newPhoneNumber
        )
        Effect
          .persist(CustomerUpdated(newFirstName, newLastName, newEmail, newPhoneNumber))
          .thenReply(replyTo)(_ => UpdateCustomerResponse(Success(updatedCustomer)))

      case GetCustomer(_, replyTo) =>
        Effect.reply(replyTo)(GetCustomerResponse(Some(state)))
    }

  // Event handler
  val eventHandler: (CustomerDetails, Event) => CustomerDetails = (state, event) =>
    event match {
      case CustomerCreated(customerId, firstName, lastName, email, phoneNumber) =>
        CustomerDetails(customerId, firstName, lastName, email, phoneNumber)

      case CustomerUpdated(newFirstName, newLastName, newEmail, newPhoneNumber) =>
        state.copy(
          firstName = newFirstName,
          lastName = newLastName,
          email = newEmail,
          phoneNumber = newPhoneNumber
        )
    }

  // Behavior definition
  def apply(customerId: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, CustomerDetails](
      persistenceId = PersistenceId.ofUniqueId(customerId),
      emptyState = CustomerDetails("", "", "", "", ""),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}
