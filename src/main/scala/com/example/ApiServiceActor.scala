package com.example

import akka.actor.{Props, Actor}

object ApiServiceActor {
  def props(domainModel: DomainModel) = Props(new ApiServiceActor(domainModel))
}

class ApiServiceActor(val domainModel: DomainModel) extends Actor with Api {

  def actorRefFactory = context

  def receive = runRoute(route)
}

trait Api extends ReleaseService {
  val route = releasesRoute
}