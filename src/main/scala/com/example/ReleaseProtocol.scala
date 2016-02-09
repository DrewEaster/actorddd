package com.example

import akka.actor.ActorRef

object ReleaseProtocol {

  case class SetView(view: ActorRef)

  case class ReleaseInfo(componentName: String, version: String, gitCommit: Option[String], gitTag: Option[String])

  case class Deployment(started: Long, ended: Option[Long] = None, length: Option[Long] = None, status: String = "IN_PROGRESS")

  case class CreateRelease(info: ReleaseInfo)

  case object StartDeployment

  case object EndDeployment

  trait ReleaseEvent extends Event {
    override val aggregateType: AggregateRootType = Release
  }

  case class ReleaseCreated(releaseId: String, info: ReleaseInfo) extends ReleaseEvent

  case class DeploymentStarted(releaseId: String, info: Deployment) extends ReleaseEvent

  case class DeploymentEnded(releaseId: String, info: Deployment) extends ReleaseEvent

}
