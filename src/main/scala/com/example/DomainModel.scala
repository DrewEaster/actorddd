package com.example

import java.util.UUID

import akka.actor.Actor.Receive
import akka.actor._
import akka.persistence.PersistentActor
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink

import scala.collection.mutable

trait Event {
  val aggregateType: String
}

trait AggregateRootType {
  def name = getClass.getName

  def template(system: ActorSystem): AggregateRootTemplate
}

trait AggregateState[S] {
  val value: S

  def updated(evt: Event): AggregateState[S]
}

trait AggregateRootTemplate {
  val system: ActorSystem

  def props(): Props

  val typeInfo: AggregateRootType

  var aggregateParent: Option[ActorRef] = None
}

class AggregateParentActor(template: AggregateRootTemplate) extends Actor with ActorLogging {
  private val runningActors: mutable.Map[UUID, ActorRef] = mutable.Map()

  private def getActor(id: UUID) = runningActors.getOrElseUpdate(id, { context.system.actorOf(template.props()) })

  override def receive = {
    case cmd @ Command(id, _) => getActor(id).forward(cmd)
  }
}

abstract class AggregateRoot[S](initialState: AggregateState[S]) extends PersistentActor with ActorLogging {
  private var internalState: AggregateState[S] = initialState

  override def persistenceId = self.path.parent.name + "-" + self.path.name

  def updateState(event: Event) {
    event match {
      case _ =>
        log.info("Updating state...")
        internalState = internalState.updated(event)
    }
  }

  def state = internalState.value
}

case class Command(id: UUID, payload: Any)

case class AggregateRootRef(id: UUID, parent: ActorRef) {
  def tell(message: Any)(implicit sender: ActorRef = null) {
    parent ! Command(id, message)
  }

  def !(message: Any)(implicit sender: ActorRef = null) {
    parent ! Command(id, message)
  }
}

trait QueryModel {

  val aggregateRootType: AggregateRootType

  val props: Props
}

// TODO: Command acknowledgement
// TODO: Sequence numbers in events for de-duping on the query model side
class DomainModel(system: ActorSystem) {

  implicit val mat = ActorMaterializer()(system)

  var aggregateRootTemplates = Map[AggregateRootType, ActorRef]()

  var queryModels = Map[Class[_ <: QueryModel], ActorRef]()

  // TODO: Hardcoded for LevelDB - must change
  val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](
    LeveldbReadJournal.Identifier)

  def register(typeInfo: AggregateRootType) = {
    val template = typeInfo.template(system)
    val parent = system.actorOf(Props(classOf[AggregateParentActor], template))
    aggregateRootTemplates = aggregateRootTemplates + (typeInfo -> parent)
    this
  }

  def registerQueryModel(queryModel: QueryModel) = {
    // TODO: Offset is from beginning of time in this case as we're using in-mem views
    // TODO: Need to deal with what happens if stream ends unexpectedly
    // TODO: No backpressure to query model - need to read up on this
    val aggregateEventSource = queries.eventsByTag(tag = queryModel.aggregateRootType.name, offset = 0L)
    val queryModelActorRef = system.actorOf(queryModel.props)
    val queryModelSink = Sink.actorRef(queryModelActorRef, "completed") // TODO: better complete message
    aggregateEventSource.map(_.event).runWith(queryModelSink)
    queryModels = queryModels + (queryModel.getClass -> queryModelActorRef)
    this
  }

  def aggregateRootOf(typeInfo: AggregateRootType, id: UUID) = {
    if (aggregateRootTemplates.contains(typeInfo)) {
      AggregateRootRef(id, aggregateRootTemplates(typeInfo))
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
