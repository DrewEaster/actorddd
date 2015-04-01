package com.example

object ReleaseProtocol {

  case class ReleaseInfo(componentName: String, version: String, gitCommit: Option[String], gitTag: Option[String])

  case class Deployment(started: Long, ended: Option[Long] = None, length: Option[Long] = None, status: String = "IN_PROGRESS")

  sealed trait Command {
    def releaseId: String
  }

  case class CreateRelease(releaseId: String, info: ReleaseInfo) extends Command

  case class StartDeployment(releaseId: String) extends Command

  case class EndDeployment(releaseId: String) extends Command

  sealed trait Event

  case class ReleaseCreated(releaseId: String, info: ReleaseInfo) extends Event

  case class DeploymentStarted(releaseId: String, info: Deployment) extends Event

  case class DeploymentEnded(releaseId: String, info: Deployment) extends Event
}
