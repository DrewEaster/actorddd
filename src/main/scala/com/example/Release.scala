package com.example

import java.util.UUID

import com.example.ReleaseProtocol._

import akka.actor._

object Release extends AggregateRootType {
  override def template(system: ActorSystem) = new ReleaseTemplate(system)
}

class ReleaseTemplate(val system: ActorSystem) extends AggregateRootTemplate {

  val typeInfo = Release

  override def props = Props(new Release())
}

case object ReleaseState {
  val initial = ReleaseState(null, ReleaseInfo("", "", None, None))
}

case class ReleaseState(id: UUID, info: ReleaseInfo, currentDeployment: Option[Deployment] = None) extends AggregateState[ReleaseState] {

  val value = this

  def updated(evt: Event): ReleaseState = evt match {
    case ReleaseCreated(releaseId, i) => copy(id = UUID.fromString(releaseId), info = i)
    case DeploymentStarted(releaseId, i) => copy(currentDeployment = Some(i))
    case DeploymentEnded(releaseId, i) => copy(currentDeployment = None)
  }
}

class Release extends AggregateRoot[ReleaseState](ReleaseState.initial) {

  import ReleaseProtocol._

  override def receiveRecover: Receive = {
    case evt: ReleaseCreated =>
      context.become(notDeploying)
      updateState(evt)
    case evt: DeploymentStarted =>
      context.become(deploying)
      updateState(evt)
    case evt: DeploymentEnded =>
      context.become(notDeploying)
      updateState(evt)
    case evt: Event => updateState(evt)
  }

  override def receiveCommand: Receive = initial

  def initial: Receive = {
    case Command(id, CreateRelease(info)) =>
      persist(ReleaseCreated(id.toString, info)) { evt =>
        updateState(evt)
        context.become(notDeploying)
      }
  }

  def notDeploying: Receive = {
    case Command(id, StartDeployment) =>
      persist(DeploymentStarted(id.toString, Deployment(started = System.currentTimeMillis()))) { evt =>
        updateState(evt)
        context.become(deploying)
      }
  }

  def deploying: Receive = {
    case Command(id, EndDeployment) =>
      state.currentDeployment.map { deployment =>
        val ended = System.currentTimeMillis()
        persist(DeploymentEnded(id.toString, deployment.copy(ended = Some(ended), length = Some(ended - deployment.started), status = "COMPLETED"))) { evt =>
          updateState(evt)
          context.become(notDeploying)
        }
      }.orElse {
        context.become(notDeploying)
        None
      }
  }
}
