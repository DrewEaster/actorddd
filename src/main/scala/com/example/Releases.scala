package com.example

import akka.actor._

object Releases {

  def props(): Props = Props(new Releases)
}

class Releases extends Actor with ActorLogging {

  import ReleasesProtocol._
  import ReleaseProtocol._


  var releases = Map[String, ReleaseDto]()

  override def receive = {
    case ReleaseCreated(id, info) => releases = releases + (id -> ReleaseDto(id, info, None, List()))
    case DeploymentStarted(id, info) => releases.get(id).map { release =>
      if (release.currentDeployment.isEmpty) releases = releases + (id -> release.copy(currentDeployment = Some(info)))
    }
    case DeploymentEnded(id, info) => releases.get(id).map { release =>
      if (release.currentDeployment.isDefined) releases = releases + (id -> release.copy(currentDeployment = None, pastDeployments = info :: release.pastDeployments.toList))
    }
    case GetRelease(id) => sender() ! releases.get(id)
    case GetReleases => sender() ! ReleasesDto(releases.values.toSeq, releases.size)
  }
}
