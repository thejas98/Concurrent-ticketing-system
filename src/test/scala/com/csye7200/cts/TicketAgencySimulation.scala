package com.csye7200.cts


import io.gatling.core.Predef._
import io.gatling.core.feeder.BatchableFeederBuilder
import io.gatling.core.structure.ScenarioBuilder
import io.gatling.http.Predef._
import io.gatling.http.protocol.HttpProtocolBuilder

import scala.concurrent.duration._

class TicketAgencySimulation extends Simulation {

  val httpConf: HttpProtocolBuilder = http.baseUrl("http://localhost:8080")
//  Test case 1: Create Event
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

 // Test case 2: Get Event


  val getEventScenario = {
    scenario("Get Event Scenario")
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
      .exec(session => {
        val eventId = session("eventLocation").as[String].split("/").last
        session.set("eventId", eventId)
      }
      )
      .exec(http("Get Event Request")
        .get("/event/${eventId}")
        .check(status.is(200))
        .check(jsonPath("$.eventName").is("Football Match"))
        .check(jsonPath("$.venue").is("Stadium"))
        .check(jsonPath("$.organizer").is("Sports Federation"))
        .check(jsonPath("$.cost").is("50.0"))
        .check(jsonPath("$.maxTickets").is("10000"))
        .check(jsonPath("$.dateTime").is("2023-12-01 18:00:00"))
        .check(jsonPath("$.duration").is("120"))
      )
  }

// Test case 3: Create a new Customer
  val createCustomerScenario = {
    scenario("Create Customer Scenario")
      .exec(http("Create Customer Request")
        .post("/customer")
        .body(StringBody(
          """{
       "firstName": "Thejas",
       "lastName": "Bharadwaj",
       "email": "blabla@gmail.com",
       "phoneNumber": "0192091092"
     }""")).asJson
        .check(status.is(201)))
  }
// Test case 4: Get Customer Details
  val getCustomerDetailsScenario = {
    scenario("Get Customer Details Scenario")
      .exec(http("Create Customer Request")
        .post("/customer")
        .body(StringBody(
          """{
          "firstName": "Thejas",
          "lastName": "Bharadwaj",
          "email": "blabla@gmail.com",
          "phoneNumber": "0192091092"
         }""")).asJson
        .check(status.is(201))
        .check(header("Location").saveAs("customerLocation"))
      )
      .exec(http("Get Customer Request")
        .get("${customerLocation}")
        .check(status.is(200))
        .check(jsonPath("$.firstName").is("Thejas"))
        .check(jsonPath("$.lastName").is("Bharadwaj"))
        .check(jsonPath("$.email").is("blabla@gmail.com"))
        .check(jsonPath("$.phoneNumber").is("0192091092"))

      )
  }


  // Test case 5: Purchase a new Ticket

  val purchaseTicketScenario = {
    scenario("Purchase Ticket Scenario")
      .exec(http("Create Customer Request")
        .post("/customer")
        .body(StringBody(
          """{
       "firstName": "Thejas",
        "lastName": "Bharadwaj",
        "email": "blabla@gmail.com",
        "phoneNumber": "0192091092"
     }""")).asJson
        .check(status.is(201))
        .check(header("Location").saveAs("customerLocation"))
      )
      .exec(http("Create Event Request")
        .post("/event")
        .body(StringBody(
          """{
       "eventName": "Music Concert",
       "venue": "Arena",
       "organizer": "Entertainment Events",
       "cost": 75.0,
       "maxTickets": 5000,
       "dateTime": "2023-12-15 20:00:00",
       "duration": 180
     }""")).asJson
        .check(status.is(201))
        .check(header("Location").saveAs("eventLocation"))
      )
      .exec(session => {
        val requestBody =
          s"""{
        "eventId": "${session("eventLocation").as[String].split("/").last}",
        "numOfTickets": 2,
        "customerID": "${session("customerLocation").as[String].split("/").last}"
      }"""
        session.set("requestBody", requestBody)
      })
      .exec(http("Purchase Ticket Request")
        .post("/ticket")
        .body(StringBody("${requestBody}")).asJson
        .check(status.is(201))
      )
  }


  // Test Case 6: Get Ticket Details (with Customer and Event Creation)
  val getTicketDetailsScenario = {
    scenario("Get Ticket Details Scenario")
      .exec(http("Create Customer Request")
        .post("/customer")
        .body(StringBody(
          """{
       "firstName": "Thejas",
        "lastName": "Bharadwaj",
        "email": "blabla@gmail.com",
        "phoneNumber": "0192091092"
     }""")).asJson
        .check(status.is(201))
        .check(header("Location").saveAs("customerLocation"))
      )
      .exec(http("Create Event and Purchase Request")
        .post("/event")
        .body(StringBody(
          """{
       "eventName": "Conference",
       "venue": "Convention Center",
       "organizer": "Business Events",
       "cost": 10.0,
       "maxTickets": 200,
       "dateTime": "2023-11-20 09:00:00",
       "duration": 240
     }""")).asJson
        .check(status.is(201))
        .check(header("Location").saveAs("eventLocation"))
      )
      .exec(session => {
        val requestBody =
          s"""{
            "eventId": "${session("eventLocation").as[String].split("/").last}",
            "numOfTickets": 2,
            "customerID": "${session("customerLocation").as[String].split("/").last}"
          }"""
        session.set("requestBody", requestBody)
      })
      .exec(http("Purchase Ticket Request")
        .post("/ticket")
        .body(StringBody("${requestBody}")).asJson
        .check(status.is(201))
        .check(header("Location").saveAs("ticketLocation"))
      )
      .exec(session => {
        val ticketId = session("ticketLocation").as[String].split("/").last
        session.set("ticketId", ticketId)
      })
      .exec(http("Get Ticket Request")
        .get("/ticket/${ticketId}")
        .check(status.is(200))
        .check(jsonPath("$.numberOfTickets").is("2"))
      )
  }

// Test Case 7: Cancel Ticket (with Customer and Event Creation)
  val cancelTicketScenario = {
    scenario("Cancel Ticket Scenario")
      .exec(http("Create Customer Request")
        .post("/customer")
        .body(StringBody(
          """{
      "firstName": "Thejas",
      "lastName": "Bharadwaj",
      "email": "blabla@gmail.com",
      "phoneNumber": "0192091092"
     }""")).asJson
        .check(status.is(201))
        .check(header("Location").saveAs("customerLocation"))
      )
      .exec(http("Create Event and Purchase Request")
        .post("/event")
        .body(StringBody(
          """{
       "eventName": "Seminar",
       "venue": "Meeting Room",
       "organizer": "Educational Institute",
       "cost": 0.0,
       "maxTickets": 50,
       "dateTime": "2023-11-25 10:00:00",
       "duration": 120
     }""")).asJson
        .check(status.is(201))
        .check(header("Location").saveAs("eventLocation"))
      )
      .exec(session => {
        val requestBody =
          s"""{
              "eventId": "${session("eventLocation").as[String].split("/").last}",
              "numOfTickets": 2,
              "customerID": "${session("customerLocation").as[String].split("/").last}"
            }"""
        session.set("requestBody", requestBody)
      })
      .exec(http("Purchase Ticket Request")
        .post("/ticket")
        .body(StringBody("${requestBody}")).asJson
        .check(status.is(201))
        .check(header("Location").saveAs("ticketLocation"))
      )
      .exec(session => {
        val cancelticketId = session("ticketLocation").as[String].split("/").last
        session.set("cancelticketId", cancelticketId)
      })
      .exec(http("Cancel Ticket Request")
        .put("/ticket/${cancelticketId}")
        .check(status.is(200))
        //add check for ticket status
      )
  }
// Test Case 8: Get All Customers
  val getAllCustomersScenario = {
    scenario("Get All Customers Scenario")
      .exec(http("Get All Customers Request")
        .get("/customers")
        .check(status.in(200,404))
      )
  }
// Test Case 9: Get All Events
  val getAllEventsScenario = {
      scenario("Get All Events Scenario")
        .exec(http("Get All Events Request")
          .get("/events")
          .check(status.in(200,404))
        )
    }


  setUp(
    createEventScenario.inject(atOnceUsers(1)),
    getEventScenario.inject(atOnceUsers(1)),
    createCustomerScenario.inject(atOnceUsers(1)),
    getCustomerDetailsScenario.inject(atOnceUsers(1)),
    purchaseTicketScenario.inject(atOnceUsers(1)),
    getTicketDetailsScenario.inject(atOnceUsers(1)),
    cancelTicketScenario.inject(atOnceUsers(1)),
    getAllCustomersScenario.inject(atOnceUsers(1)),
    getAllEventsScenario.inject(atOnceUsers(1))
  ).protocols(httpConf)
}
