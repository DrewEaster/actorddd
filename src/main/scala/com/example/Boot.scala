package com.example

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import akka.io.IO
import akka.persistence.journal.leveldb.{SharedLeveldbJournal, SharedLeveldbStore}
import com.example.ReleaseProtocol.SetView
import com.typesafe.config.ConfigFactory
import spray.can.Http
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._

object Boot extends App {

  val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=2551").
    withFallback(ConfigFactory.load())

  implicit val system = ActorSystem("ClusterSystem", config)

  startupSharedJournal(system, path = ActorPath.fromString("akka.tcp://ClusterSystem@127.0.0.1:2551/user/store"))

  val domainModel = new DomainModel(system).register(Release)
  val releasesView = system.actorOf(Releases.props(), "releases-view")

  val service = system.actorOf(ApiServiceActor.props(domainModel, releasesView), "demo-service")

  implicit val timeout = new Timeout(5.seconds)
  // start a new HTTP server on port 8080 with our service actor as the handler
  IO(Http) ? Http.Bind(service, interface = "localhost", port = 8080)

  private def startupSharedJournal(system: ActorSystem, path: ActorPath): Unit = {
    system.actorOf(Props[SharedLeveldbStore], "store")

    // register the shared journal
    import system.dispatcher
    implicit val timeout = Timeout(15.seconds)
    val f = (system.actorSelection(path) ? Identify(None))
    f.onSuccess {
      case ActorIdentity(_, Some(ref)) => SharedLeveldbJournal.setStore(ref, system)
      case _ =>
        system.log.error("Shared journal not started at {}", path)
        system.shutdown()
    }
    f.onFailure {
      case _ =>
        system.log.error("Lookup of shared journal at {} timed out", path)
        system.shutdown()
    }
  }
}
