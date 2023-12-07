package com.csye7200.cts
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class HeavyLoadTestingBookingTickets extends Simulation {

  val baseURL = "http://localhost:8080"

  val httpProtocol = http.baseUrl(baseURL)

  //create a single event request - eventID
  val createEventScenario = {
    scenario("Create Event Scenario")
      .exec(http("Create Event Request")
        .post("/event")
        .body(StringBody(
          """{
         "eventName": "Football Match",
         "venue": "Stadium",
         "organizer": "Sports Federation",
         "cost": 50.0,
         "maxTickets": 10000,
         "dateTime": "2023-12-01 18:00:00",
         "duration": 120
       }""")).asJson
        .check(status.is(201))
        .check(header("Location").saveAs("eventLocation"))
      )
  }
  //create 1000 customers
  val createCustomerScenario = {
    scenario("Create Customer Scenario")
      .repeat(1000, "counter") {
          exec(http("Create Customer Request")
            .post("/customer")
            .body(StringBody(
              """{
           "firstName": "Thejas",
           "lastName": "Bharadwaj",
           "email": "blabla@gmail.com",
           "phoneNumber": "0192091092"
         }""")).asJson
            .check(status.is(201)))
  }}

  //send get all customers call - customerIDS
  val getAllCustomersScenario = {
    scenario("Get All Customers Scenario")
      .exec(http("Get All Customers Request")
        .get("/customers")
        .check(status.in(200, 404))
      )
  }
  val ticketFeeder = csv("buyTicketRequest.csv").random

  val buyTicketScenario = {
    scenario("Buy Ticket Scenario")
      .feed(ticketFeeder)
      .exec(session => {
        val requestBody =
                s"""{
            "eventId": "${session("eventId").as[String]}",
            "numOfTickets": "${session("numOfTickets").as[Int]}",
            "customerID": "${session("customerID").as[String]}"
          }"""
              session.set("requestBody", requestBody)
        })
      .exec(http("Buy Ticket Request")
        .post("/ticket")
        .requestTimeout(3.minutes)
        .body(StringBody("${requestBody}")).asJson
        .check(status.in(201,400,500))
      )

  }


  //send buy ticket with customer ids and eventid
  setUp(
    buyTicketScenario.inject(atOnceUsers(1000))
  ).protocols(httpProtocol)
}
