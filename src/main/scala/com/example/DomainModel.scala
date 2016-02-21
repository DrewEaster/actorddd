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
  def props: Props
}

trait AggregateState[S] {
  val value: S

  def updated(evt: Event): AggregateState[S]
}

class AggregateParentActor(typeInfo: AggregateRootType) extends Actor with ActorLogging {
  private val runningActors: mutable.Map[UUID, ActorRef] = mutable.Map()

  private def getActor(id: UUID) = runningActors.getOrElseUpdate(id, { context.system.actorOf(typeInfo.props) })

  override def receive = {
    case cmd @ Command(id, _) => getActor(id).forward(cmd)
  }
}

abstract class SimpleAggregateRoot[S, E <: Event](initialState: AggregateState[S]) extends AggregateRoot[S](initialState) {
  type CommandEvent = PartialFunction[Command, E]
  type EventHandler = PartialFunction[E, Option[PartialFunction[Command, E]]]

  def commandHandler = new SimpleAggregateRootCommandHandler(events, this, uninitialised)

  def events: EventHandler

  override def receiveRecover: Receive = new SimpleAggregateRootEventHandler(events, this, commandHandler)

  override def receiveCommand: Receive = commandHandler

  def uninitialised: CommandEvent
}

class SimpleAggregateRootEventHandler[S, E <: Event](events: PartialFunction[E, Option[PartialFunction[Command, E]]], root: AggregateRoot[S], handler: SimpleAggregateRootCommandHandler[S, E]) extends PartialFunction[Any, Unit] {
  override def isDefinedAt(x: Any): Boolean = x match {
    case event: E ⇒ events.isDefinedAt(event)
    case _ ⇒ false
  }

  override def apply(v1: Any): Unit = v1 match {
    case event: E ⇒
      root.updateState(event)
      handler.switchIfNeeded(event)
  }
}

class SimpleAggregateRootCommandHandler[S, E <: Event](nextState: PartialFunction[E, Option[PartialFunction[Command, E]]], root: AggregateRoot[S], initial: PartialFunction[Command, E]) extends PartialFunction[Any, Unit] {
  private var currentFunction = initial

  override def isDefinedAt(x: Any): Boolean = x match {
    case cmd: Command ⇒ currentFunction.isDefinedAt(cmd)
    case _ ⇒ false
  }

  override def apply(v1: Any): Unit = v1 match {
    case cmd: Command ⇒
      val event = currentFunction(cmd)
      root.persist(event) { event ⇒
        root.updateState(event)
        root.context.system.eventStream.publish(event)
        switchIfNeeded(event)
      }
  }

  def switchIfNeeded(event: E) = {
    if (nextState.isDefinedAt(event)) {
      currentFunction = nextState(event).getOrElse(emptyBehavior)
    }
  }

  object emptyBehavior extends PartialFunction[Command, E] {
    def isDefinedAt(x: Command) = false
    def apply(x: Command) = throw new UnsupportedOperationException("Empty behavior apply()")
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
