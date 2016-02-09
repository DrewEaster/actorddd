package com.example

import akka.persistence.journal.{Tagged, WriteEventAdapter}

class AggregateTaggingEventAdapter extends WriteEventAdapter {
  override def toJournal(event: Any): Any = event match {
    case e: Event => Tagged(event, Set(e.aggregateType.name))
    case _ => event
  }

  override def manifest(event: Any): String = ""
}