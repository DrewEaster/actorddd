package com.example

import scala.concurrent.duration._
import akka.actor._
import akka.contrib.pattern.ShardRegion
import akka.contrib.pattern.ShardRegion.Passivate
import akka.persistence.PersistentActor

object Release {

  import ReleaseProtocol._

  def props(releasesView: ActorRef): Props = Props(new Release(releasesView))

  val idExtractor: ShardRegion.IdExtractor = {
    case cmd: Command => (cmd.releaseId, cmd)
  }

  val shardResolver: ShardRegion.ShardResolver = msg => msg match {
    case cmd: Command => (math.abs(cmd.releaseId.hashCode) % 100).toString
  }

  val shardName: String = "Release"


  private case class State(info: ReleaseInfo, currentDeployment: Option[Deployment] = None) {

    def updated(evt: Event): State = evt match {
      case ReleaseCreated(releaseId, i) => copy(info = i)
      case DeploymentStarted(releaseId, i) => copy(currentDeployment = Some(i))
      case DeploymentEnded(releaseId, i) => copy(currentDeployment = None)
    }
  }
}

class Release(releasesView: ActorRef) extends PersistentActor with ActorLogging {

  import Release._
  import ReleaseProtocol._

  // self.path.parent.name is the type name (utf-8 URL-encoded)
  // self.path.name is the entry identifier (utf-8 URL-encoded)
  override def persistenceId: String = self.path.parent.name + "-" + self.path.name

  // passivate the entity when no activity
  context.setReceiveTimeout(2.minutes)

  private var state = State(ReleaseInfo("", "", None, None))

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
    case CreateRelease(id, info) =>
      persist(ReleaseCreated(id, info)) { evt =>
        state = state.updated(evt)
        context.become(notDeploying)
        releasesView ! evt
        log.info("Release created: " + evt)
      }
  }

  def notDeploying: Receive = {
    case StartDeployment(id) =>
      persist(DeploymentStarted(id, Deployment(started = System.currentTimeMillis()))) { evt =>
        state = state.updated(evt)
        context.become(deploying)
        releasesView ! evt
        log.info("Deployment started: " + evt)
      }
  }

  def deploying: Receive = {
    case EndDeployment(id) =>
      state.currentDeployment.map { deployment =>
        val ended = System.currentTimeMillis()
        persist(DeploymentEnded(id, deployment.copy(ended = Some(ended), length = Some(ended - deployment.started), status = "COMPLETED"))) { evt =>
          state = state.updated(evt)
          context.become(notDeploying)
          releasesView ! evt
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
