package com.example

import akka.actor.{Props, ActorRef}
import akka.persistence.{PersistentView, PersistentActor}

trait AggregateRoot extends PersistentActor {

  val queryModel: ActorRef

  override def persistenceId: String = self.path.parent.name + "-" + self.path.name

  // Create the corresponding view for this AR instance. Forwards on to aggregated query model for this aggregate root type
  context.actorOf(AggregatingView.props(persistenceId, queryModel))
}

object AggregatingView {
  def props(persistenceId: String, queryModel: ActorRef) = Props(new AggregatingView(persistenceId, queryModel))
}

class AggregatingView(val persistenceId: String, queryModel: ActorRef) extends PersistentView {

  val viewId = persistenceId

  override def receive = {
    case event => queryModel ! event
  }
}
