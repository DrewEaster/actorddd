package com.example

import java.util.UUID

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

  def props(id: UUID): Props
}

class AggregateRootNotInitializedException extends Exception

class AggregateParentActor(typeInfo: AggregateRootType) extends Actor with ActorLogging {
  private val runningActors: mutable.Map[UUID, ActorRef] = mutable.Map()

  private def getActor(id: UUID) = runningActors.getOrElseUpdate(id, {
    context.system.actorOf(typeInfo.props(id))
  })

  override def receive = {
    case cmd@CommandWrapper(id, _) => getActor(id).forward(cmd.payload)
  }
}

abstract class SimpleAggregateRoot[S, E <: Event](id: UUID) extends AggregateRoot[S] {
  type CommandEvent = PartialFunction[Command, E]
  type EventHandler = PartialFunction[E, Option[PartialFunction[Command, E]]]

  override def receiveRecover: Receive = {
    case event: E if events.isDefinedAt(event) ⇒
      updateState(event)
      maybeBecome(event)
  }

  override def receiveCommand: Receive = wrapPartial(uninitialised)

  def events: EventHandler
  def uninitialised: CommandEvent

  private def maybeBecome(event: E): Unit = getPartial(event).foreach { next ⇒ context.become(next) }
  private def getPartial(event: E): Option[Receive] = events(event).map(wrapPartial)
  private def wrapPartial(handler: CommandEvent): Receive = {
    case cmd: Command if handler.isDefinedAt(cmd) ⇒
      persist(handler(cmd)) { event ⇒
        updateState(event)
        context.system.eventStream.publish(event)
        maybeBecome(event)
      }
  }
}

abstract class AggregateRoot[S] extends PersistentActor with ActorLogging {
  type AggregateStateFactory = PartialFunction[Event, S]

  def factory: AggregateStateFactory
  def next(event: Event): S

  def state = _state.getOrElse(throw new AggregateRootNotInitializedException)
  def updateState(event: Event) =
    _state = _state.map(_ ⇒ next(event)).orElse(Some(factory.apply(event)))
  private var _state: Option[S] = None

  override def persistenceId = self.path.parent.name + "-" + self.path.name
}

case class CommandWrapper(id: UUID, payload: Command)

trait Command

case class AggregateRootRef(id: UUID, parent: ActorRef) {
  def tell(message: Command)(implicit sender: ActorRef = null) {
    parent ! CommandWrapper(id, message)
  }

  def !(message: Command)(implicit sender: ActorRef = null) {
    parent ! CommandWrapper(id, message)
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

  var aggregateRootParents = Map[AggregateRootType, ActorRef]()

  var queryModels = Map[Class[_ <: QueryModel], ActorRef]()

  // TODO: Hardcoded for LevelDB - must change
  val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](
    LeveldbReadJournal.Identifier)

  def register(typeInfo: AggregateRootType) = {
    val parent = system.actorOf(Props(classOf[AggregateParentActor], typeInfo))
    aggregateRootParents = aggregateRootParents + (typeInfo -> parent)
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
    if (aggregateRootParents.contains(typeInfo)) {
      AggregateRootRef(id, aggregateRootParents(typeInfo))
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
