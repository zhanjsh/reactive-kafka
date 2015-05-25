package com.softwaremill.react.kafka

import akka.stream.actor.{ActorPublisher, ActorPublisherMessage}
import com.softwaremill.react.kafka.KafkaActorPublisher.Poll
import kafka.consumer.{ConsumerTimeoutException, KafkaConsumer}
import kafka.serializer.Decoder

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
private[kafka] class KafkaActorPublisher[T](consumer: KafkaConsumer, decoder: Decoder[T]) extends ActorPublisher[T] {

  val iterator = consumer.iterator()

  override def receive = {
    case ActorPublisherMessage.Request(_) | Poll => readDemandedItems()
    case ActorPublisherMessage.Cancel | ActorPublisherMessage.SubscriptionTimeoutExceeded => cleanupResources()
  }

  private def tryReadingSingleElement() = {
    Try(iterator.next().message()).map(bytes => Some(decoder.fromBytes(bytes))).recover {
      // We handle timeout exceptions as normal 'end of the queue' cases
      case _: ConsumerTimeoutException => None
    }
  }

  @tailrec
  private def readDemandedItems() {
      tryReadingSingleElement() match {
        case Success(None) =>
          if (totalDemand > 0)
            self ! Poll
        case Success(valueOpt) =>
          valueOpt.foreach(element => onNext(element))
          if (totalDemand > 0) readDemandedItems()
        case Failure(ex) => onError(ex)
    }
  }

  private def cleanupResources() {
    consumer.close()
    context.stop(self)
  }
}

private[kafka] object KafkaActorPublisher {
  case object Poll
}