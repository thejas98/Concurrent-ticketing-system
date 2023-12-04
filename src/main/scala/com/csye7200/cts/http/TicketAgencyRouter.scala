package com.csye7200.cts.http


import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{CacheDirectives, Location, RawHeader, `Cache-Control`}
import akka.http.scaladsl.server.Directives._
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.server.Directive0
import akka.util.Timeout
import com.csye7200.cts.actors.Customer.CustomerCommand.{CreateCustomer, GetCustomer}
import com.csye7200.cts.actors.Customer.CustomerResponse.{CustomerCreatedResponse, GetCustomerResponse}
import com.csye7200.cts.actors.Customer.{CustomerCommand, CustomerResponse}
import com.csye7200.cts.actors.CustomerManager.{GetAllCustomers, GetAllCustomersResponse}
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import com.csye7200.cts.actors.TicketActor.{TicketSellerCommand, TicketSellerResponse}
import com.csye7200.cts.actors.Event.EventCommand._
import com.csye7200.cts.actors.Event.EventResponse
import com.csye7200.cts.actors.Event.EventCommand
import com.csye7200.cts.actors.Event.EventResponse.{EventCreatedResponse, EventUpdatedResponse, GetEventResponse}
import com.csye7200.cts.actors.EventManager.{GetAllEvents, GetAllEventsResponse}
import com.csye7200.cts.actors.TicketActor.TicketSellerCommand.{BuyTicket, CancelTicket, GetTicket}
import com.csye7200.cts.actors.TicketActor.TicketSellerResponse.{CancellationResponse, GetTicketResponse, PurchaseResponse}

import javax.naming.ldap.Control
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

case class EventCreationRequest(eventName: String, venue: String, organizer: String, cost: Double, maxTickets: Int, dateTime: String, duration: Int) {
  def toCommand(replyTo: ActorRef[EventResponse]): EventCommand = CreateEvent(eventName: String, venue: String, organizer: String, cost: Double, maxTickets: Int, dateTime: String, duration: Int, replyTo)
}

case class EventUpdateRequest(ticketCount: Int) {
  def toCommand(id: String, replyTo: ActorRef[EventResponse]): EventCommand = UpdateEvent(id, ticketCount, replyTo)
}

case class TicketPurchaseRequest(eventId: String, numOfTickets: Int, customerID: String) {
  def toCommand(eventManager: ActorRef[EventCommand], replyTo: ActorRef[TicketSellerResponse]): TicketSellerCommand = BuyTicket(eventManager, eventId, numOfTickets, customerID, replyTo)
}

case class CustomerCreationRequest(firstName: String, lastName: String, email: String, phoneNumber: String) {
  def toCommand(replyTo: ActorRef[CustomerResponse]): CustomerCommand = CreateCustomer(firstName, lastName, email, phoneNumber, replyTo)
}

case class FailureResponse(reason: String)

class TicketAgencyRouter(eventManager: ActorRef[EventCommand], ticketManager: ActorRef[TicketSellerCommand], customerManager: ActorRef[CustomerCommand])(implicit system: ActorSystem[_]) {

  implicit val timeout: Timeout = Timeout(5.seconds)

  // Transform event requests to commands
  def createEventRequest(request: EventCreationRequest): Future[EventResponse] =
    eventManager.ask(replyTo => request.toCommand(replyTo))

  def getEvent(id: String): Future[EventResponse] =
    eventManager.ask(replyTo => GetEvent(id, replyTo))

  def updateEvent(id: String, request: EventUpdateRequest): Future[EventResponse] =
    eventManager.ask(replyTo => request.toCommand(id, replyTo))


  // Transform ticket requests to commands
  def purchaseTicketRequest(request: TicketPurchaseRequest): Future[TicketSellerResponse] =
    ticketManager.ask(replyTo => request.toCommand(eventManager, replyTo))

  def getTicket(ticketID: String): Future[TicketSellerResponse] =
    ticketManager.ask(replyTo => GetTicket(ticketID, replyTo))

  def cancelTicket(ticketID: String): Future[TicketSellerResponse] =
    ticketManager.ask(replyTo => CancelTicket(eventManager,ticketID, replyTo))

  // Transform customer requests to commands
  def createCustomerRequest(request: CustomerCreationRequest): Future[CustomerResponse] =
    customerManager.ask(replyTo => request.toCommand(replyTo))

  def getCustomer(customerID: String): Future[CustomerResponse] =
    customerManager.ask(replyTo => GetCustomer(customerID, replyTo))

  def getCustomers(): Future[CustomerResponse] =
    customerManager.ask(replyTo => GetAllCustomers(replyTo))

  def getEvents(): Future[EventResponse] =
    eventManager.ask(replyTo => GetAllEvents(replyTo))


  val routes =
    pathPrefix("event") {
      pathEndOrSingleSlash {
        post {
          // parse the payload
          entity(as[EventCreationRequest]) {
            request =>
              /*
              - convert the request into a command
              - send the command to the Event Manager
              - expect a reply

               */
              onSuccess(createEventRequest(request)) {
                case EventCreatedResponse(eventId) =>
                  // - send back an HTTP response
                  respondWithHeaders(Location(s"/event/$eventId"), RawHeader("Cache-Control", "no-store")) {
                    complete(StatusCodes.Created)
                  }
              }
          }
        }
      } ~
        path(Segment) {

          id =>
            get {
              /*
               -send command to the event manager
               - send the command to the event
               -expect a reply
                */

                onSuccess(getEvent(id)) {
                  case GetEventResponse(Some(maybeEvent)) =>
                    respondWithHeader(RawHeader("Cache-Control", "no-store")) {
                      complete(maybeEvent)
                    }
                  case GetEventResponse(None) =>
                    complete(StatusCodes.NotFound, FailureResponse(s"Event $id is not a valid event. Request for another event"))
                }

            } ~
              put {
                /*
                 -Transform the request to a command
                 - send the command to the event
                 -expect a reply
                  */
                // Don't need this. as we are going to update the event through Ticket Manager.
                entity(as[EventUpdateRequest]) {
                  request =>
                    onSuccess(updateEvent(id, request)) {
                      case EventUpdatedResponse(Some(maybeEvent)) =>
                        complete(maybeEvent)
                      case EventUpdatedResponse(None) =>
                        complete(StatusCodes.NotFound, FailureResponse(s"Event $id is not a valid event"))
                    }
                }
              }
        }
    } ~
      pathPrefix("ticket") {
        pathEndOrSingleSlash {
          post {
            // parse the payload
            entity(as[TicketPurchaseRequest]) {
              request =>
                onSuccess(purchaseTicketRequest(request)) {
                  case PurchaseResponse(tickets) =>
                    // - send back an HTTP response
                    val ticketStatus = tickets.get.ticketStatus
                    ticketStatus match {
                      case "Unsuccessful" =>
                        respondWithHeader(Location(s"/ticket/${tickets.get.ticketID}")) {
                          complete(StatusCodes.Created, FailureResponse(s"Requested ticket count exceeded per transaction or tickets sold out. "))
                        }
                      case "Successful" =>
                        respondWithHeader(Location(s"/ticket/${tickets}")) {
                          complete(StatusCodes.Created)
                        }
                      case "Event-Unavailable" =>
                        respondWithHeader(Location(s"/ticket/${tickets.get.ticketID}")) {
                          complete(StatusCodes.Created, FailureResponse(s"Requested event not available "))
                        }
                    }

                }
            }
          }
        } ~
          path(Segment) {
            ticketID =>
              get {
                onSuccess(getTicket(ticketID)) {
                  case GetTicketResponse(Some(tickets)) =>
                    complete(tickets)
                  case GetTicketResponse(None) =>
                    complete(StatusCodes.NotFound, FailureResponse(s"Ticket ID $ticketID ID invalid. Try a valid ticket number to get booking details"))
                }
              } ~
                put {
                      onSuccess(cancelTicket(ticketID)) {
                        case CancellationResponse(Some(tickets)) =>
                          complete(tickets)
                        case CancellationResponse(None) =>
                          complete(StatusCodes.NotFound, FailureResponse(s"Ticket ID $ticketID invalid or already cancelled"))
                      }
                }
          }
} ~ pathPrefix("customer") {
  pathEndOrSingleSlash {
    post {
      // parse the payload
      entity(as[CustomerCreationRequest]) {
        request =>
          onSuccess(createCustomerRequest(request)) {
            case CustomerCreatedResponse(customerId) =>
              // - send back an HTTP response
              respondWithHeader(Location(s"/customer/$customerId")) {
                complete(StatusCodes.Created)
              }
          }
      }
    } ~
      path(Segment) {
        customerID =>
          get {
            onSuccess(getCustomer(customerID)) {
              case GetCustomerResponse(Some(customer)) =>
                complete(customer)
              case GetCustomerResponse(None) =>
                complete(StatusCodes.NotFound, FailureResponse(s"Customer ID $customerID is invalid. No such customer in Ticket Agency"))
            }
          }
      }
  }
} ~ pathPrefix("customers") {
  get {
    onSuccess(getCustomers()) {
      case GetAllCustomersResponse(Some(customers)) =>
        complete(customers)
      case GetAllCustomersResponse(None) =>
        complete(StatusCodes.NotFound, FailureResponse("No customers have registered with the Ticketing Agency"))
    }
  }
} ~ pathPrefix("events") {
  get {
    onSuccess(getEvents()) {
      case GetAllEventsResponse(Some(events)) =>
        complete(events)
      case GetAllEventsResponse(None) =>
        complete(StatusCodes.NotFound, FailureResponse("Events coming soon. Check back later!"))
    }
  }
}
}

