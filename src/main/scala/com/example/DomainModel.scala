package com.example

import java.util.UUID

import akka.actor._
import akka.contrib.pattern.ShardRegion.Passivate
import akka.contrib.pattern.{ClusterSharding, ShardRegion}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, PersistentView}

trait Event

trait AggregateRootType {
  def template(system: ActorSystem): AggregateRootTemplate
}

object AggregateRootTemplate {
  val idExtractor: ShardRegion.IdExtractor = {
    case cmd: Command => (cmd.id.toString, cmd)
  }

  val shardResolver: ShardRegion.ShardResolver = {
    case cmd: Command => (math.abs(cmd.id.toString.hashCode) % 100).toString
  }
}

trait AggregateState[S] {
  val value: S

  def updated(evt: Event): AggregateState[S]
}

trait AggregateRootTemplate {

  import AggregateRootTemplate._

  val system: ActorSystem

  def props(aggregator: ActorPath): Props

  val typeInfo: AggregateRootType

  lazy val eventAggregator = system.actorOf(EventAggregator.props(typeInfo))

  lazy val region = ClusterSharding(system).start(
    typeName = typeInfo.getClass.getName,
    entryProps = Some(props(eventAggregator.path)),
    idExtractor = idExtractor,
    shardResolver = shardResolver)

  def aggregateRootOf(id: UUID) = AggregateRootRef(id, region)
}

case class ConfirmableEvent(deliveryId: Long, msg: Any)

case class Confirm(deliveryId: Long)

case class ConfirmedEvent(deliveryId: Long) extends Event

abstract class AggregateRoot[S](initialState: AggregateState[S], aggregator: ActorPath) extends PersistentActor with AtLeastOnceDelivery with ActorLogging {

  import scala.concurrent.duration._

  context.setReceiveTimeout(2.minutes)

  private var internalState: AggregateState[S] = initialState

  override def persistenceId = self.path.parent.name + "-" + self.path.name

  def updateState(event: Event) {
    event match {
      case ConfirmedEvent(deliveryId) => confirmDelivery(deliveryId)
      case _ =>
        log.info("Updating state...")
        internalState = internalState.updated(event)
        log.info("Delivering event to aggregator: " + aggregator.toStringWithoutAddress)
        deliver(aggregator, id => ConfirmableEvent(id, event))
    }
  }

  def state = internalState.value

  override def unhandled(msg: Any): Unit = msg match {
    case Confirm(deliveryId) => persist(ConfirmedEvent(deliveryId))(updateState)
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _ => super.unhandled(msg)
  }
}

case class Command(id: UUID, payload: Any)

case class AggregateRootRef(id: UUID, region: ActorRef) {
  def tell(message: Any)(implicit sender: ActorRef = null) {
    region ! Command(id, message)
  }

  def !(message: Any)(implicit sender: ActorRef = null) {
    region ! Command(id, message)
  }
}

object EventAggregator {
  def props(aggregateRootType: AggregateRootType) = Props(new EventAggregator(aggregateRootType))
}

class EventAggregator(val aggregateRootType: AggregateRootType) extends PersistentActor with ActorLogging {

  override def persistenceId = "event-aggregator-" + aggregateRootType.getClass.getSimpleName.toLowerCase

  override def receiveRecover = {
    case _ => // do nothing as we don't need in memory state for the aggregator
  }

  override def receiveCommand = {
    case ConfirmableEvent(deliveryId, event) =>
      persist(event) { evt =>
        sender() ! Confirm(deliveryId)
      }
  }
}

trait QueryModel {

  val aggregateRootType: AggregateRootType

  val props: Props
}

object EventAggregatorView {
  def props(viewName: String, aggregateRootType: AggregateRootType, queryModel: ActorRef) = Props(new EventAggregatorView(viewName, aggregateRootType, queryModel))
}

class EventAggregatorView(val viewName: String, val aggregateRootType: AggregateRootType, queryModel: ActorRef) extends PersistentView {

  override def persistenceId = "event-aggregator-" + aggregateRootType.getClass.getSimpleName.toLowerCase

  override def viewId = "views-" + aggregateRootType.getClass.getSimpleName.toLowerCase + "-" + viewName

  override def receive = {
    case event => queryModel ! event
  }
}

// TODO: Command acknowledgement
// TODO: Sequence numbers in events for de-duping on the query model side
class DomainModel(system: ActorSystem) {

  var aggregateRootTemplates = Map[AggregateRootType, AggregateRootTemplate]()

  var queryModels = Map[Class[_ <: QueryModel], ActorRef]()

  def register(typeInfo: AggregateRootType) = {
    val template = typeInfo.template(system)
    aggregateRootTemplates = aggregateRootTemplates + (typeInfo -> template)
    this
  }

  def registerQueryModel(queryModel: QueryModel) = {
    val queryModelActorRef = system.actorOf(queryModel.props)
    system.actorOf(EventAggregatorView.props(queryModel.getClass.getName.toLowerCase, queryModel.aggregateRootType, queryModelActorRef))
    queryModels = queryModels + (queryModel.getClass -> queryModelActorRef)
    this
  }

  def aggregateRootOf(typeInfo: AggregateRootType, id: UUID) = {
    if (aggregateRootTemplates.contains(typeInfo)) {
      aggregateRootTemplates(typeInfo).aggregateRootOf(id)
    } else {
      throw new IllegalArgumentException("The aggregate root type is not supported by this domain model!")
    }
  }

  def queryModelOf(queryModel: QueryModel) = {
    if (queryModels.contains(queryModel.getClass)) {
      queryModels(queryModel.getClass)
    } else {
      throw new IllegalArgumentException("No query model registered with the given name!")
    }
  }
}


