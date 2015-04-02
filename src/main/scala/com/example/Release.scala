package com.example

import java.util.UUID

import com.example.ReleaseProtocol._

import scala.concurrent.duration._
import akka.actor._
import akka.contrib.pattern.ShardRegion.Passivate

object Release extends AggregateRootType {
  override def template(system: ActorSystem) = new ReleaseTemplate(system)
}

class ReleaseTemplate(val system: ActorSystem) extends AggregateRootTemplate {

  val typeInfo = Release

  override def props(queryModel: ActorRef) = Props(new Release(queryModel))

  override val queryModelProps = Props[Releases]
}

case class ReleaseState(id: UUID, info: ReleaseInfo, currentDeployment: Option[Deployment] = None) {
  def updated(evt: Event): ReleaseState = evt match {
    case ReleaseCreated(releaseId, i) => copy(id = UUID.fromString(releaseId), info = i)
    case DeploymentStarted(releaseId, i) => copy(currentDeployment = Some(i))
    case DeploymentEnded(releaseId, i) => copy(currentDeployment = None)
  }
}

class Release(val queryModel: ActorRef) extends AggregateRoot with ActorLogging {

  import ReleaseProtocol._

  context.setReceiveTimeout(2.minutes)

  private var state = ReleaseState(null, ReleaseInfo("", "", None, None))

  override def receiveRecover: Receive = {
    case evt: ReleaseCreated =>
      context.become(notDeploying)
      state = state.updated(evt)
    case evt: DeploymentStarted =>
      context.become(deploying)
      state = state.updated(evt)
    case evt: DeploymentEnded =>
      context.become(notDeploying)
      state = state.updated(evt)
    case evt: Event => state =
      state.updated(evt)
  }

  override def receiveCommand: Receive = initial

  def initial: Receive = {
    case Command(id, CreateRelease(info)) =>
      persist(ReleaseCreated(id.toString, info)) { evt =>
        state = state.updated(evt)
        context.become(notDeploying)
        log.info("Release created: " + evt)
      }
  }

  def notDeploying: Receive = {
    case Command(id, StartDeployment) =>
      persist(DeploymentStarted(id.toString, Deployment(started = System.currentTimeMillis()))) { evt =>
        state = state.updated(evt)
        context.become(deploying)
        log.info("Deployment started: " + evt)
      }
  }

  def deploying: Receive = {
    case Command(id, EndDeployment) =>
      state.currentDeployment.map { deployment =>
        val ended = System.currentTimeMillis()
        persist(DeploymentEnded(id.toString, deployment.copy(ended = Some(ended), length = Some(ended - deployment.started), status = "COMPLETED"))) { evt =>
          state = state.updated(evt)
          context.become(notDeploying)
          log.info("Deployment ended: " + evt)
        }
      }.orElse {
        context.become(notDeploying)
        None
      }
  }

  override def unhandled(msg: Any): Unit = msg match {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _ => super.unhandled(msg)
  }
}
