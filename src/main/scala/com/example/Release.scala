package com.example

import java.util.UUID

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import akka.actor._
import akka.contrib.pattern.ShardRegion.Passivate
import akka.persistence.PersistentActor

object Release extends AggregateRootType {

  import ReleaseProtocol._

  val typeInfo: Class[_ <: PersistentActor] = classOf[Release]
  val props: Props = Props[Release]
  val name: String = "Release"

  private case class State(id: UUID, info: ReleaseInfo, currentDeployment: Option[Deployment] = None) {
    def updated(evt: Event): State = evt match {
      case ReleaseCreated(releaseId, i) => copy(id = UUID.fromString(releaseId), info = i)
      case DeploymentStarted(releaseId, i) => copy(currentDeployment = Some(i))
      case DeploymentEnded(releaseId, i) => copy(currentDeployment = None)
    }
  }

}

class Release extends PersistentActor with ActorLogging {

  import Release._
  import ReleaseProtocol._
  import scala.concurrent.ExecutionContext.Implicits.global

  var releasesView: Option[ActorRef] = Some(Await.ready(context.actorSelection(self.path.root / "user" / "releases-view").resolveOne()(5 seconds), 5 seconds).value.get.get)

  override def persistenceId: String = self.path.parent.name + "-" + self.path.name

  context.setReceiveTimeout(2.minutes)

  // FIXME: This is very unsafe


  println(self.path.root / "user" / "releases-view")

  private var state = State(null, ReleaseInfo("", "", None, None))

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
        releasesView.map(_ ! evt)
        log.info("Release created: " + evt)
      }
  }

  def notDeploying: Receive = {
    case Command(id, StartDeployment) =>
      persist(DeploymentStarted(id.toString, Deployment(started = System.currentTimeMillis()))) { evt =>
        state = state.updated(evt)
        context.become(deploying)
        releasesView.map(_ ! evt)
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
          releasesView.map(_ ! evt)
          log.info("Deployment ended: " + evt)
        }
      }.orElse {
        context.become(notDeploying)
        None
      }
  }

  override def unhandled(msg: Any): Unit = msg match {
    case SetView(view) => releasesView = Some(view)
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _ => super.unhandled(msg)
  }
}
