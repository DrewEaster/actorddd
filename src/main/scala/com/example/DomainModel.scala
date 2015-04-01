package com.example

import java.util.UUID

import akka.actor.{ActorRef, Props, ActorSystem}
import akka.contrib.pattern.{ClusterSharding, ShardRegion}
import akka.persistence.PersistentActor

trait AggregateRootType {
  val typeInfo: Class[_ <: PersistentActor]
  val name: String
  val props: Props
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

class DomainModel(system: ActorSystem) {

  val idExtractor: ShardRegion.IdExtractor = {
    case cmd: Command => (cmd.id.toString, cmd)
  }

  val shardResolver: ShardRegion.ShardResolver = msg => msg match {
    case cmd: Command => (math.abs(cmd.id.toString.hashCode) % 100).toString
  }

  var aggregateRoots = Map[Class[_ <: PersistentActor], ActorRef]()

  def register(aggregateRootType: AggregateRootType) = {
    val ar = ClusterSharding(system).start(
      typeName = aggregateRootType.name,
      entryProps = Some(aggregateRootType.props),
      idExtractor = idExtractor,
      shardResolver = shardResolver)
    aggregateRoots = aggregateRoots + (aggregateRootType.typeInfo -> ar)
    this
  }

  def aggregateRootOf(aggregateRootType: AggregateRootType, id: UUID) = {
    if (aggregateRoots.contains(aggregateRootType.typeInfo)) {
      AggregateRootRef(id, aggregateRoots(aggregateRootType.typeInfo))
    } else {
      throw new IllegalArgumentException("The aggregate root type is not supported by this domain model!")
    }
  }
}


