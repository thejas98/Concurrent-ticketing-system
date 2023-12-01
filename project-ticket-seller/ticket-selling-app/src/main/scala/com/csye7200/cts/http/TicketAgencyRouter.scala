package com.csye7200.cts.http

import com.csye7200.cts.actors.TicketActor.TicketSellerCommand
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Directives._
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import .EventCommand._
import .EventResponse
import .EventCommand
import .EventResponse.{EventCreatedResponse, EventUpdatedResponse, GetEventResponse}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

case class EventCreationRequest(eventName: String, venue: String, organizer: String, cost: Double, maxTickets: Int, dateTime: String, duration: Int) {
  def toCommand(replyTo: ActorRef[EventResponse]): EventCommand = CreateEvent(eventName: String, venue: String, organizer: String, cost: Double, maxTickets: Int, dateTime: String, duration: Int, replyTo)
}

case class EventUpdateRequest(ticketCount: Int) {
  def toCommand(id: String, replyTo: ActorRef[EventResponse]): EventCommand = UpdateEvent(id, ticketCount, replyTo)
}

case class FailureResponse(reason: String)

class TicketAgencyRouter(eventManager: ActorRef[EventCommand], ticketManager: ActorRef[TicketSellerCommand])(implicit system: ActorSystem[_]) {

  implicit val timeout: Timeout = Timeout(5.seconds)

  def createEventRequest(request: EventCreationRequest): Future[EventResponse] =
    eventManager.ask(replyTo => request.toCommand(replyTo))

  def getEvent(id: String): Future[EventResponse] =
    eventManager.ask(replyTo => GetEvent(id, replyTo))

  def updateEvent(id: String, request: EventUpdateRequest): Future[EventResponse] =
    eventManager.ask(replyTo => request.toCommand(id, replyTo))

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
                  respondWithHeader(Location(s"/event/$eventId")) {
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
                  complete(maybeEvent)
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
    }
  //      pathPrefix("customer") {
  //        pathEndOrSingleSlash {
  //          post {
  //
  //          } ~
  //            get {
  //
  //            }
  //        }
  //      } ~
}

