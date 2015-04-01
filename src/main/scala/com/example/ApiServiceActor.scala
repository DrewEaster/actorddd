package com.example

import akka.actor.{Props, ActorRef, Actor}

object ApiServiceActor {
  def props(releaseRegion: ActorRef, releasesView: ActorRef) = Props(new ApiServiceActor(releaseRegion, releasesView))
}

class ApiServiceActor(val releaseRegion: ActorRef, val releasesView: ActorRef) extends Actor with Api {

  println(self.path)

  def actorRefFactory = context

  def receive = runRoute(route)
}

trait Api extends ReleaseService {
  val route = releasesRoute
}