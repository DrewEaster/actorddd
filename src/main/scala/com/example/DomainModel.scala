package com.example

import java.util.UUID

import akka.actor._
import akka.persistence.PersistentActor
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink

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

  lazy val aggregateRegion = system.actorOf(props())

  def aggregateRootOf(id: UUID) = AggregateRootRef(id, aggregateRegion)
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

case class AggregateRootRef(id: UUID, region: ActorRef) {
  def tell(message: Any)(implicit sender: ActorRef = null) {
    region ! Command(id, message)
  }

  def !(message: Any)(implicit sender: ActorRef = null) {
    region ! Command(id, message)
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

  var aggregateRootTemplates = Map[AggregateRootType, AggregateRootTemplate]()

  var queryModels = Map[Class[_ <: QueryModel], ActorRef]()

  // TODO: Hardcoded for LevelDB - must change
  val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](
    LeveldbReadJournal.Identifier)

  def register(typeInfo: AggregateRootType) = {
    val template = typeInfo.template(system)
    aggregateRootTemplates = aggregateRootTemplates + (typeInfo -> template)
    this
  }

  def registerQueryModel(queryModel: QueryModel) = {
    // TODO: Offset is from beginning of time in this case as we're using in-mem views
    // TODO: Need to deal with what happens if stream ends unexpectedly
    // TODO: No backpressure to query model - need to read up on this
    val aggregateEventSource = queries.eventsByTag(tag = queryModel.aggregateRootType.name, offset = 0L)
    val queryModelActorRef = system.actorOf(queryModel.props)
    val queryModelSink = Sink.actorRef(queryModelActorRef, "completed") // TODO: better complete message
    aggregateEventSource.runWith(queryModelSink)
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

