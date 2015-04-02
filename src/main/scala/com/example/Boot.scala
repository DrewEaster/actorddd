package com.example

import akka.actor._
import akka.io.IO
import com.typesafe.config.ConfigFactory
import spray.can.Http
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._

object Boot extends App {

  val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=2551").
    withFallback(ConfigFactory.load())

  implicit val system = ActorSystem("ClusterSystem", config)

  // Start the shared event journal
  new SharedJournal(system, path = ActorPath.fromString("akka.tcp://ClusterSystem@127.0.0.1:2551/user/store"))

  // Initialise the domain model
  // At the moment, only registering a single aggregate root type 'Release'
  val domainModel = new DomainModel(system).register(Release)

  val service = system.actorOf(ApiServiceActor.props(domainModel), "demo-service")

  implicit val timeout = new Timeout(5.seconds)
  // start a new HTTP server on port 8080 with our service actor as the handler
  IO(Http) ? Http.Bind(service, interface = "localhost", port = 8080)
}
