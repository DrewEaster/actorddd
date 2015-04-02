package com.example

import akka.actor._
import akka.persistence.journal.leveldb.{SharedLeveldbJournal, SharedLeveldbStore}
import akka.util.Timeout
import scala.concurrent.duration._
import akka.pattern.ask

class SharedJournal(system: ActorSystem, path: ActorPath) {

  import system.dispatcher

  system.actorOf(Props[SharedLeveldbStore], "store")

  implicit val timeout = Timeout(15.seconds)
  val f = (system.actorSelection(path) ? Identify(None))
  f.onSuccess {
    case ActorIdentity(_, Some(ref)) => SharedLeveldbJournal.setStore(ref, system)
    case _ =>
      system.log.error("Shared journal not started at {}", path)
      system.shutdown()
  }
  f.onFailure {
    case _ =>
      system.log.error("Lookup of shared journal at {} timed out", path)
      system.shutdown()
  }
}
