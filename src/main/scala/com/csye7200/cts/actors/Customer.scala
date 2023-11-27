package com.rockthejvm.ticketing.actors

import akka.NotUsed
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

object CustomerActor {

  // Commands
  sealed trait Command
  object Command {
    case class CreateCustomer(firstName: String, lastName: String, email: String, phoneNumber: String, replyTo: ActorRef[Response]) extends Command
    case class GetCustomer(replyTo: ActorRef[Response]) extends Command
    case class UpdateCustomer(firstName: String, lastName: String, email: String, phoneNumber: String, replyTo: ActorRef[Response]) extends Command
  }

  // Events
  sealed trait Event
  case class CustomerCreated(firstName: String, lastName: String, email: String, phoneNumber: String) extends Event
  case class CustomerUpdated(firstName: String, lastName: String, email: String, phoneNumber: String) extends Event

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
        val customerId = UUID.randomUUID().toString
        val customerDetails = CustomerDetails(customerId, firstName, lastName, email, phoneNumber)
        Effect
          .persist(CustomerCreated(firstName, lastName, email, phoneNumber))
          .thenReply(replyTo)(_ => CustomerCreatedResponse(customerId))

      case GetCustomer(replyTo) =>
        Effect.reply(replyTo)(GetCustomerResponse(Some(state)))

      case UpdateCustomer(firstName, lastName, email, phoneNumber, replyTo) =>
        val updatedCustomer = state.copy(firstName = firstName, lastName = lastName, email = email, phoneNumber = phoneNumber)
        Effect
          .persist(CustomerUpdated(firstName, lastName, email, phoneNumber))
          .thenReply(replyTo)(_ => UpdateCustomerResponse(Success(updatedCustomer)))
    }

  // Event handler
  val eventHandler: (CustomerDetails, Event) => CustomerDetails = (state, event) =>
    event match {
      case CustomerCreated(firstName, lastName, email, phoneNumber) =>
        state.copy(firstName = firstName, lastName = lastName, email = email, phoneNumber = phoneNumber)

      case CustomerUpdated(firstName, lastName, email, phoneNumber) =>
        state.copy(firstName = firstName, lastName = lastName, email = email, phoneNumber = phoneNumber)
    }

  // Behavior definition
  def apply(): Behavior[Command] = Behaviors.setup { context =>
    EventSourcedBehavior[Command, Event, CustomerDetails](
      persistenceId = PersistenceId.ofUniqueId("customer"),
      emptyState = CustomerDetails("", "", "", "", ""),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
  }
}

object CustomerActorPlayground {
  import CustomerActor.Command._
  import CustomerActor.Response._
  import CustomerActor.Response

  def main(args: Array[String]): Unit = {
    val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context =>
      val customerActor = context.spawn(CustomerActor(), "customerActor")
      val logger = context.log

      val responseHandler = context.spawn(Behaviors.receiveMessage[Response] {
        case CustomerCreatedResponse(customerId) =>
          logger.info(s"Successfully created customer $customerId")
          Behaviors.same
        case GetCustomerResponse(maybeCustomer) =>
          logger.info(s"Customer details: $maybeCustomer")
          Behaviors.same
        case UpdateCustomerResponse(maybeUpdatedCustomer) =>
          logger.info(s"Updated customer details: $maybeUpdatedCustomer")
          Behaviors.same
      }, "replyHandler")

      // ask pattern
      import akka.actor.typed.scaladsl.AskPattern._
      import scala.concurrent.duration._
      implicit val timeout: Timeout = Timeout(2.seconds)
      implicit val scheduler: Scheduler = context.system.scheduler
      implicit val ec: ExecutionContext = context.executionContext

      // Usage examples
      // customerActor ! CreateCustomer("John", "Doe", "john.doe@example.com", "+1234567890", responseHandler)
      // customerActor ! GetCustomer(responseHandler)
      // customerActor ! UpdateCustomer("John", "Doe", "john.doe@example.com", "+9876543210", responseHandler)

      Behaviors.empty
    }

    val system = ActorSystem(rootBehavior, "CustomerActorDemo")
  }
}
