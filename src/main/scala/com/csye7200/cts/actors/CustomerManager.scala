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

object CustomerManager {

  // commands = messages
  import Customer.Command._
  import Customer.Response._
  import Customer.Command
  import Customer.Response

  // command for Get All Events
  case class GetAllCustomers(replyTo : ActorRef[Response] ) extends Command

  // Response for Get All Events
  case class GetAllCustomersResponse(allCustomers: Option[List[String]]) extends Response

  // events
  sealed trait Event
  case class CustomerCreated(customerID: String) extends Event

  // state
  case class State(customers: Map[String, ActorRef[Command]])

  // command handler
  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] = (state, command) =>
    command match {
      case createCommand @ CreateCustomer(firstName,lastName,email,phoneNumber, replyTo) =>
        val customerID = "customerID-"+UUID.randomUUID().toString
        val newCustomer = context.spawn(Customer(customerID), customerID)
        Effect
          .persist(CustomerCreated(customerID))
          .thenReply(newCustomer)(_ => createCommand)

      case getCmd @ GetCustomer(customerID, replyTo) =>
        state.customers.get(customerID) match {
          case Some(customer) =>
            Effect.reply(customer)(getCmd)
          case None =>
            Effect.reply(replyTo)(GetCustomerResponse(None))
        }
      case getAllCustomers @ GetAllCustomers(replyTo) =>
        val allCustomers = state.customers.keys.toList
        Effect.reply(replyTo)(GetAllCustomersResponse(Some(allCustomers)))
    }

  // event handler
  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event) =>
    event match {
      case CustomerCreated(customerID) =>
        val customerActor = context.child(customerID)
          .getOrElse(context.spawn(Customer(customerID), customerID))
          .asInstanceOf[ActorRef[Command]]
        state.copy(state.customers + (customerID -> customerActor))
    }

  // behavior
  def apply(): Behavior[Command] = Behaviors.setup { context =>
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId("customer-management"),
      emptyState = State(Map()),
      commandHandler = commandHandler(context),
      eventHandler = eventHandler(context)
    )
  }
}

object CustomerManagementPlayground {
  import Customer.Command._
  import Customer.Response._
  import Customer.Response
  import CustomerManager.GetAllCustomersResponse
  import CustomerManager.GetAllCustomers
  def main(args: Array[String]): Unit = {
    val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context =>
      val customerManagement = context.spawn(CustomerManager(), "customerManagement")
      val logger = context.log

      val responseHandler = context.spawn(Behaviors.receiveMessage[Response] {
        case CustomerCreatedResponse(customerID) =>
          logger.info(s"Successfully created Customer $customerID")
          Behaviors.same
        case GetCustomerResponse(maybeCustomer) =>
          logger.info(s"Customer details: $maybeCustomer")
          Behaviors.same
        case GetAllCustomersResponse(allCustomers) =>
          logger.info(s"All Customers: $allCustomers")
          Behaviors.same
      }, "replyHandler")

      // ask pattern
      import scala.concurrent.duration._
      implicit val timeout: Timeout = Timeout(2.seconds)
      implicit val scheduler: Scheduler = context.system.scheduler
      implicit val ec: ExecutionContext = context.executionContext
//      customerManagement ! CreateCustomer("John", "Doe", "john.doe@example.com", "+1234567890", responseHandler)
      customerManagement ! GetAllCustomers(responseHandler)



      Behaviors.empty
    }

    val system = ActorSystem(rootBehavior, "CustomerManagementDemo")
  }
}
