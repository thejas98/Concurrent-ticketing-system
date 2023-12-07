package com.csye7200.cts


import io.gatling.core.structure.ScenarioBuilder
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import io.gatling.http.protocol.HttpProtocolBuilder

class HeavyLoadTesting extends Simulation {

    val httpConf: HttpProtocolBuilder = http.baseUrl("http://localhost:8080")
    val csvFeeder_createEvent = csv("createEventRequest.csv").random
    val createEventScenario = scenario("Create Event Scenario")
      .feed(csvFeeder_createEvent)
      .exec(session => {
        val requestBody = s"""{
        "eventName": "${session("eventName").as[String]}",
        "venue": "${session("venue").as[String]}",
        "organizer": "${session("organizer").as[String]}",
        "cost": ${session("cost").as[Double]},
        "maxTickets": ${session("maxTickets").as[Int]},
        "dateTime": "${session("dateTime").as[String]}",
        "duration": ${session("duration").as[Int]}
      }"""
        session.set("requestBody", requestBody)
      })
      .exec(http("Create Event Request")
        .post("/event")
        .requestTimeout(3.minutes)
        .body(StringBody("${requestBody}")).asJson
        .check(status.in(201,500,400))
      )
  val csvFeeder_createCustomer = csv("createCustomerRequest.csv").random
  val createCustomerScenario = scenario("Create Customer Scenario")
    .feed(csvFeeder_createCustomer)
    .exec(session => {
      val requestBody =
        s"""{
    "firstName": "${session("firstName").as[String]}",
    "lastName": "${session("lastName").as[String]}",
    "email": "${session("phoneNumber").as[String]}",
    "phoneNumber": "${session("phoneNumber").as[String]}"
      }"""
      session.set("requestBody", requestBody)
    })
    .exec(http("Create Customer Request")
      .post("/customer")
      .requestTimeout(3.minutes)
      .body(StringBody("${requestBody}")).asJson
      .check(status.in(201,500,400))
    )

  setUp(
    createEventScenario.inject(atOnceUsers(1250))
      .andThen(createCustomerScenario.inject(atOnceUsers(1250)))

  ).protocols(httpConf)
}
