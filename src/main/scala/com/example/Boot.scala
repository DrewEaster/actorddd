package com.example

import java.util.UUID

import akka.actor._
import akka.io.IO
import com.example.ReleaseProtocol.{ReleaseInfo, CreateRelease}
import com.example.ReleasesProtocol.{ReleasesDto, GetReleases}
import com.typesafe.config.ConfigFactory
import spray.can.Http
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object Boot extends App {

  val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=2551").
    withFallback(ConfigFactory.load())

  implicit val system = ActorSystem("ClusterSystem", config)

  implicit val timeout = Timeout(2 seconds)

  // Start the shared event journal
  new SharedJournal(system, path = ActorPath.fromString("akka.tcp://ClusterSystem@127.0.0.1:2551/user/store"))

  // Initialise the domain model with the Release aggregate root and the single query model
  val domainModel = new DomainModel(system)
    .register(Release)
    .registerQueryModel(Releases)

  val releaseId = UUID.randomUUID()
  val releaseOne = domainModel.aggregateRootOf(Release, releaseId)
  releaseOne ! CreateRelease(ReleaseInfo("component1", "1.2", None, None))

  Thread.sleep(2000)

  val releases = domainModel.queryModelOf(Releases)
  (releases ? GetReleases).map {
    case ReleasesDto(r, t) => r.foreach(println)
  }
}
