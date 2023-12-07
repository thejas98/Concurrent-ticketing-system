# Ticket Agency System

## Overview

Welcome to a groundbreaking venture aimed at redefining the ticketing experience for sports enthusiasts.
Our vision is to create a ticket agency that excels in handling events of all sizes, from local matches to international tournaments, with effortless ease.
At the heart of this project are four key pillars: Scalability, Concurrency, Reliability, and Performance.
Our mission is to provide users with a seamless, reliable, and high-performance platform, ensuring they enjoy swift and frustration-free ticket purchasing, from event selection to checkout.
We're committed to robust data persistence, user-friendly interfaces, regulatory compliance, real-time support, and comprehensive documentation.
Join us on this transformative journey as we set out to revolutionize the world of ticketing.

## Table of Contents

1. [Getting Started](#getting-started)
    - [Prerequisites](#prerequisites)
    - [Installation](#installation)
    - [Configuration](#configuration)
2. [Usage](#usage)
    - [Running the Application](#running-the-application)
    - [API Endpoints](#api-endpoints)
3. [Testing](#testing)
    - [Unit Tests](#unit-tests)
    - [Integration and Performance Testing](#Integration-and-Performance-Testing)
    
4. [Contributing](#contributing)
5. [License](#license)
6. [Acknowledgments](#acknowledgments)

## Getting Started

### Prerequisites

- Tools and Technologies:
1) Scala (built with 2.13.x)
2) Akka actors and Akka HTTP
3) Cassandra
4) Docker and Docker Compose
   
### Installation

1. Clone the repository:

    ```bash
    git clone https://github.com/thejas98/Concurrent-ticketing-system.git
    cd concurrent-ticketing-system
    ```

2. Import the project into IntelliJ and make sure to have the scala plugin for IntelliJ.
3. Load the sbt Project using the sbt tool or the command line for sbt.

## Usage

### Running the Application

1. Before starting the application start the cassandra server by running the docker compose file using the command :
   `docker compose up`
2. Start the Http server by running the file called TicketAgencyApp.scala located at src/main/scala/com/csye7200/cts/app/TicketAgencyApp.scala.
3. Now you can use Postman or curl command from CMD to test that the server is up.


### API Endpoints

- **Event Management:**
    - `POST /event`: Create a new event.
    - `GET /event/{eventId}`: Get details of a specific event.
    - `PUT /event/{eventId}`: Update an existing event.
    - `GET /events`: Get all events available.

- **Ticket Management:**
    - `POST /ticket`: Purchase tickets for an event.
    - `GET /ticket/{ticketId}`: Get details of a specific ticket.
    - `PUT /ticket/{ticketId}`: Cancel a purchased ticket.

- **Customer Management:**
    - `POST /customer`: Create a new customer.
    - `GET /customer/{customerId}`: Get details of a specific customer.
    - `GET /customers`: Get a list of all customers.
    - 

### Testing

All the testing is conducted using the Gatling tool. Gatling is a powerful load-testing solution for applications, APIs, and microservices.

Refer to the following document to see a list of all test cases with decriptions and expected result.

#### Unit Tests

1. To run the unit tests for the application, run the following command from the root directory:
   `sbt Gatling/test`
2. After running the above line, a report gets generated under `'target/Gatling'`. Each folder under this directory represents one run of the test case. You can open the `index.html` file under any one report folder to see the report in pdf.
   

#### Integration and Performance Testing

1. To run the testing for heavy loads of 1000+ concurrent users, we have prepared two test files:
   - HeavyLoadTesting : Create 1000+ Events and customers.
   - HeavyLoadTestingBookingTickets: Book 1000+ tickets for 1000+ customers for a specific event.
2. To run the above test cases, use the following format to run:
   ```sbt 'GatlingIt/testOnly com.csye7200.cts.HeavyLoadTesting'```



Note: All the test cases are documents in the testcases.csv file located in the root directory.

## Contributing

If you'd like to contribute to this project, please follow these guidelines.

1. Fork the repository.
2. Create a new branch for your feature: `git checkout -b feature-name`
3. Commit your changes: `git commit -m 'Add some feature'`
4. Push to your branch: `git push origin feature-name`
5. Open a pull request.

## License

This project is licensed under the [Your License] - see the [LICENSE.md](LICENSE.md) file for details.

## Acknowledgments

- [Any acknowledgments, credits, or references you want to include]

