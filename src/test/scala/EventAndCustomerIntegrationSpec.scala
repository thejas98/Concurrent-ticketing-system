import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Scheduler
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import com.csye7200.cts.actors.{CustomerManager, Event}
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

class EventAndCustomerIntegrationSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  override implicit val timeout: Timeout = Timeout(3.seconds)
  implicit val scheduler: Scheduler = system.scheduler
  implicit val ec: ExecutionContext = system.executionContext

  "An EventAndCustomerIntegrationSpec" must {

    "create an event, create a customer, and the customer actor gets event details" in {
      val eventManager = testKit.spawn(Event("event-1"))
      val customerActor = testKit.spawn(CustomerManager("customer-1"))

      val eventDetails = Event.EventDetails(
        "event-1",
        "ScalaCon",
        "Convention Center",
        "Scala Community",
        100.0,
        500,
        "2023-11-30 09:00:00",
        240
      )

      val createEventCommand = Event.Command.CreateEvent(
        eventDetails.eventName,
        eventDetails.venue,
        eventDetails.organizer,
        eventDetails.cost,
        eventDetails.maxTickets,
        eventDetails.dateTime,
        eventDetails.duration,
        testKit.createTestProbe[Event.Response].ref
      )

      val eventCreatedResponse = testKit.ask(eventManager, createEventCommand).futureValue

      eventCreatedResponse shouldBe a[Event.Response.EventCreatedResponse]

      val getEventCommand = Event.Command.GetEvent("event-1", testKit.createTestProbe[Event.Response].ref)
      val eventDetailsResponse = testKit.ask(eventManager, getEventCommand).futureValue

      eventDetailsResponse shouldBe Event.Response.GetEventResponse(Some(eventDetails))

      val createCustomerCommand = CustomerManager.Command.CreateCustomer(
        "John",
        "Doe",
        "john.doe@example.com",
        "+1234567890",
        testKit.createTestProbe[CustomerManager.Response].ref
      )

      val customerCreatedResponse = testKit.ask(customerActor, createCustomerCommand).futureValue

      customerCreatedResponse shouldBe a[CustomerManager.Response.CustomerCreatedResponse]

      val getCustomerCommand = CustomerManager.Command.GetCustomer("customer-1", testKit.createTestProbe[CustomerManager.Response].ref)
      val customerDetailsResponse = testKit.ask(customerActor, getCustomerCommand).futureValue

      customerDetailsResponse shouldBe CustomerManager.Response.GetCustomerResponse(
        Some(
          CustomerManager.CustomerDetails(
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
