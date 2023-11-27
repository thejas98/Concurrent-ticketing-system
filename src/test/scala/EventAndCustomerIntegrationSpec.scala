import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Scheduler
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import com.csye7200.cts.actors.{PersistentCustomer, PersistentEventManager}
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

class EventAndCustomerIntegrationSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  override implicit val timeout: Timeout = Timeout(3.seconds)
  implicit val scheduler: Scheduler = system.scheduler
  implicit val ec: ExecutionContext = system.executionContext

  "An EventAndCustomerIntegrationSpec" must {

    "create an event, create a customer, and the customer actor gets event details" in {
      val eventManager = testKit.spawn(PersistentEventManager("event-1"))
      val customerActor = testKit.spawn(PersistentCustomer("customer-1"))

      val eventDetails = PersistentEventManager.EventDetails(
        "event-1",
        "ScalaCon",
        "Convention Center",
        "Scala Community",
        100.0,
        500,
        "2023-11-30 09:00:00",
        240
      )

      val createEventCommand = PersistentEventManager.Command.CreateEvent(
        eventDetails.eventName,
        eventDetails.venue,
        eventDetails.organizer,
        eventDetails.cost,
        eventDetails.maxTickets,
        eventDetails.dateTime,
        eventDetails.duration,
        testKit.createTestProbe[PersistentEventManager.Response].ref
      )

      val eventCreatedResponse = testKit.ask(eventManager, createEventCommand).futureValue

      eventCreatedResponse shouldBe a[PersistentEventManager.Response.EventCreatedResponse]

      val getEventCommand = PersistentEventManager.Command.GetEvent("event-1", testKit.createTestProbe[PersistentEventManager.Response].ref)
      val eventDetailsResponse = testKit.ask(eventManager, getEventCommand).futureValue

      eventDetailsResponse shouldBe PersistentEventManager.Response.GetEventResponse(Some(eventDetails))

      val createCustomerCommand = PersistentCustomer.Command.CreateCustomer(
        "John",
        "Doe",
        "john.doe@example.com",
        "+1234567890",
        testKit.createTestProbe[PersistentCustomer.Response].ref
      )

      val customerCreatedResponse = testKit.ask(customerActor, createCustomerCommand).futureValue

      customerCreatedResponse shouldBe a[PersistentCustomer.Response.CustomerCreatedResponse]

      val getCustomerCommand = PersistentCustomer.Command.GetCustomer("customer-1", testKit.createTestProbe[PersistentCustomer.Response].ref)
      val customerDetailsResponse = testKit.ask(customerActor, getCustomerCommand).futureValue

      customerDetailsResponse shouldBe PersistentCustomer.Response.GetCustomerResponse(
        Some(
          PersistentCustomer.CustomerDetails(
            "customer-1",
            "John",
            "Doe",
            "john.doe@example.com",
            "+1234567890"
          )
        )
      )
    }
  }
}
