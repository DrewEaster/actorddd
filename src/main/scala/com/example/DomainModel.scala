package com.example

import java.util.UUID

import akka.actor._
import akka.contrib.pattern.{ClusterSharding, ShardRegion}

trait AggregateRootType {
  def template(system: ActorSystem): AggregateRootTemplate
}

object AggregateRootTemplate {
  val idExtractor: ShardRegion.IdExtractor = {
    case cmd: Command => (cmd.id.toString, cmd)
  }

  val shardResolver: ShardRegion.ShardResolver = msg => msg match {
    case cmd: Command => (math.abs(cmd.id.toString.hashCode) % 100).toString
  }
}

trait AggregateRootTemplate {

  import AggregateRootTemplate._

  val system: ActorSystem

  def props(queryModel: ActorRef): Props

  val queryModelProps: Props
  val typeInfo: AggregateRootType

  lazy val view = system.actorOf(queryModelProps)

  lazy val region = ClusterSharding(system).start(
    typeName = typeInfo.getClass.getName,
    entryProps = Some(props(view)),
    idExtractor = idExtractor,
    shardResolver = shardResolver)

  def aggregateRootOf(id: UUID) = AggregateRootRef(id, region)
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

  var aggregateRootTemplates = Map[AggregateRootType, AggregateRootTemplate]()

  def register(typeInfo: AggregateRootType) = {
    val template = typeInfo.template(system)
    aggregateRootTemplates = aggregateRootTemplates + (typeInfo -> template)
    this
  }

  def aggregateRootOf(typeInfo: AggregateRootType, id: UUID) = {
    if (aggregateRootTemplates.contains(typeInfo)) {
      aggregateRootTemplates(typeInfo).aggregateRootOf(id)
    } else {
      throw new IllegalArgumentException("The aggregate root type is not supported by this domain model!")
    }
  }

  def viewOf(typeInfo: AggregateRootType) = {
    if (aggregateRootTemplates.contains(typeInfo)) {
      aggregateRootTemplates(typeInfo).view
    } else {
      throw new IllegalArgumentException("The aggregate root type is not supported by this domain model!")
    }
  }
}


