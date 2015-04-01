package com.example

import akka.actor.{Props, ActorRef, Actor}

object ApiServiceActor {
  def props(domainModel: DomainModel, releasesView: ActorRef) = Props(new ApiServiceActor(domainModel, releasesView))
}

class ApiServiceActor(val domainModel: DomainModel, val releasesView: ActorRef) extends Actor with Api {

  println(self.path)

  def actorRefFactory = context

  def receive = runRoute(route)
}

trait Api extends ReleaseService {
  val route = releasesRoute
}