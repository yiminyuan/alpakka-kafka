/*
 * Copyright (C) 2014 - 2016 Softwaremill <http://softwaremill.com>
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.kafka

import java.util
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, Status, Terminated}
import akka.event.LoggingReceive
import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import scala.collection.JavaConverters._
import java.util.concurrent.locks.LockSupport
import akka.actor.DeadLetterSuppression
import scala.util.control.{NoStackTrace, NonFatal}
import scala.util.{Failure, Success, Try}

object KafkaConsumerActor {
  case class StoppingException() extends RuntimeException("Kafka consumer is stopping")
  def props[K, V](settings: ConsumerSettings[K, V]): Props =
    Props(new KafkaConsumerActor(settings)).withDispatcher(settings.dispatcher)

  private[kafka] object Internal {
    //requests
    final case class Assign(tps: Set[TopicPartition])
    final case class AssignWithOffset(tps: Map[TopicPartition, Long])
    final case class Subscribe(topics: Set[String], listener: ConsumerRebalanceListener)
    final case class SubscribePattern(pattern: String, listener: ConsumerRebalanceListener)
    final case class RequestMessages(requestId: Int, topics: Set[TopicPartition])
    case object Stop
    final case class Commit(offsets: Map[TopicPartition, Long])
    //responses
    final case class Assigned(partition: List[TopicPartition])
    final case class Revoked(partition: List[TopicPartition])
    final case class Messages[K, V](requestId: Int, messages: Iterator[ConsumerRecord[K, V]])
    final case class Committed(offsets: Map[TopicPartition, OffsetAndMetadata])
    //internal
    private[KafkaConsumerActor] final case class Poll[K, V](target: KafkaConsumerActor[K, V]) extends DeadLetterSuppression
    private val number = new AtomicInteger()
    def nextNumber() = {
      number.incrementAndGet()
    }

    private[KafkaConsumerActor] class NoPollResult extends RuntimeException with NoStackTrace
  }

  private[kafka] def rebalanceListener(onAssign: Iterable[TopicPartition] => Unit, onRevoke: Iterable[TopicPartition] => Unit) = new ConsumerRebalanceListener {
    override def onPartitionsAssigned(partitions: util.Collection[TopicPartition]): Unit = {
      onAssign(partitions.asScala)
    }
    override def onPartitionsRevoked(partitions: util.Collection[TopicPartition]): Unit = {
      onRevoke(partitions.asScala)
    }
  }

  private class WrappedAutoPausedListener(client: KafkaConsumer[_, _], listener: ConsumerRebalanceListener) extends ConsumerRebalanceListener {
    override def onPartitionsAssigned(partitions: util.Collection[TopicPartition]): Unit = {
      client.pause(partitions)
      listener.onPartitionsAssigned(partitions)
    }

    override def onPartitionsRevoked(partitions: util.Collection[TopicPartition]): Unit = {
      listener.onPartitionsRevoked(partitions)
    }
  }
}

private[kafka] class KafkaConsumerActor[K, V](settings: ConsumerSettings[K, V])
    extends Actor with ActorLogging {
  import KafkaConsumerActor.Internal._
  import KafkaConsumerActor._

  val pollMsg = Poll(this)
  def pollTimeout() = settings.pollTimeout
  def pollInterval() = settings.pollInterval

  var currentPollTask: Cancellable = _

  var requests = Map.empty[ActorRef, RequestMessages]
  var consumer: KafkaConsumer[K, V] = _
  var commitsInProgress = 0
  var wakeups = 0
  var stopInProgress = false

  def receive: Receive = LoggingReceive {
    case Assign(tps) =>
      checkOverlappingRequests("Assign", sender(), tps)
      val previousAssigned = consumer.assignment()
      consumer.assign((tps.toSeq ++ previousAssigned.asScala).asJava)
    case AssignWithOffset(tps) =>
      checkOverlappingRequests("AssignWithOffset", sender(), tps.keySet)
      val previousAssigned = consumer.assignment()
      consumer.assign((tps.keys.toSeq ++ previousAssigned.asScala).asJava)
      tps.foreach {
        case (tp, offset) => consumer.seek(tp, offset)
      }
    case Commit(offsets) =>
      val commitMap = offsets.mapValues(new OffsetAndMetadata(_))
      val reply = sender()
      commitsInProgress += 1
      consumer.commitAsync(commitMap.asJava, new OffsetCommitCallback {
        override def onComplete(offsets: util.Map[TopicPartition, OffsetAndMetadata], exception: Exception): Unit = {
          // this is invoked on the thread calling consumer.poll which will always be the actor, so it is safe
          commitsInProgress -= 1
          if (exception != null) reply ! Status.Failure(exception)
          else reply ! Committed(offsets.asScala.toMap)
        }
      })
      //right now we can not store commits in consumer - https://issues.apache.org/jira/browse/KAFKA-3412
      poll()
    case Subscribe(topics, listener) =>
      consumer.subscribe(topics.toList.asJava, new WrappedAutoPausedListener(consumer, listener))
    case SubscribePattern(pattern, listener) =>
      consumer.subscribe(Pattern.compile(pattern), new WrappedAutoPausedListener(consumer, listener))
    case Poll(target) =>
      if (target == this) {
        poll()
        currentPollTask = schedulePollTask()
      }
      else {
        // Message was enqueued before a restart - can be ignored
        log.debug("Ignoring Poll message with stale target ref")
      }

    case req: RequestMessages =>
      context.watch(sender())
      checkOverlappingRequests("RequestMessages", sender(), req.topics)
      requests = requests.updated(sender(), req)
      poll()
    case Stop =>
      if (commitsInProgress == 0) {
        context.stop(self)
      }
      else {
        stopInProgress = true
        context.become(stopping)
      }
    case Terminated(ref) =>
      requests -= ref
  }

  def checkOverlappingRequests(updateType: String, fromStage: ActorRef, topics: Set[TopicPartition]): Unit = {
    // check if same topics/partitions have already been requested by someone else,
    // which is an indication that something is wrong, but it might be alright when assignments change.
    if (requests.nonEmpty) requests.foreach {
      case (ref, r) =>
        if (ref != fromStage && r.topics.exists(topics.apply)) {
          log.warning("{} from topic/partition {} already requested by other stage {}", updateType, topics, r.topics)
          ref ! Messages(r.requestId, Iterator.empty)
          requests -= ref
        }
    }
  }

  def stopping: Receive = LoggingReceive {
    case Poll(target) =>
      if (target == this) {
        poll()
        currentPollTask = schedulePollTask()
      }
      else {
        // Message was enqueued before a restart - can be ignored
        log.debug("Ignoring Poll message with stale target ref")
      }

    case Stop =>
    case _: Terminated =>
    case msg @ (_: Commit | _: RequestMessages) =>
      sender() ! Status.Failure(StoppingException())
    case msg @ (_: Assign | _: AssignWithOffset | _: Subscribe | _: SubscribePattern) =>
      log.warning("Got unexpected message {} when KafkaConsumerActor is in stopping state", msg)
  }

  override def preStart(): Unit = {
    super.preStart()

    consumer = settings.createKafkaConsumer()
    currentPollTask = schedulePollTask()
  }

  override def postStop(): Unit = {
    if (currentPollTask != null)
      currentPollTask.cancel()

    // reply to outstanding requests is important if the actor is restarted
    requests.foreach {
      case (ref, req) =>
        ref ! Messages(req.requestId, Iterator.empty)
    }
    consumer.close()
    super.postStop()
  }

  def schedulePollTask(): Cancellable =
    context.system.scheduler.scheduleOnce(pollInterval(), self, pollMsg)(context.dispatcher)

  def poll() = {
    val wakeupTask = context.system.scheduler.scheduleOnce(settings.wakeupTimeout) {
      consumer.wakeup()
    }(context.system.dispatcher)
    //set partitions to fetch
    val partitionsToFetch: Set[TopicPartition] = requests.values.flatMap(_.topics)(collection.breakOut)
    consumer.assignment().asScala.foreach { tp =>
      if (partitionsToFetch.contains(tp)) consumer.resume(java.util.Collections.singleton(tp))
      else consumer.pause(java.util.Collections.singleton(tp))
    }

    def tryPoll(timeout: Long): ConsumerRecords[K, V] =
      try {
        val records = consumer.poll(timeout)
        wakeups = 0
        records
      }
      catch {
        case w: WakeupException =>
          wakeups = wakeups + 1
          if (wakeups == settings.maxWakeups) {
            log.error("WakeupException limit exceeded, stopping.")
            context.stop(self)
          }
          else {
            log.warning(s"Consumer interrupted with WakeupException after timeout. Message: ${w.getMessage}. " +
              s"Current value of akka.kafka.consumer.wakeup-timeout is ${settings.wakeupTimeout}")
          }
          throw new NoPollResult
        case NonFatal(e) =>
          log.error(e, "Exception when polling from consumer")
          context.stop(self)
          throw new NoPollResult
      }

    try {
      if (requests.isEmpty) {
        // no outstanding requests so we don't expect any messages back, but we should anyway
        // drive the KafkaConsumer by polling

        def checkNoResult(rawResult: ConsumerRecords[K, V]): Unit =
          if (!rawResult.isEmpty)
            throw new IllegalStateException(s"Got ${rawResult.count} unexpected messages")

        checkNoResult(tryPoll(0))

        // For commits we try to avoid blocking poll because a commit normally succeeds after a few
        // poll(0). Using poll(1) will always block for 1 ms, since there are no messages.
        // Therefore we do 10 poll(0) with short 10 μs delay followed by 1 poll(1).
        // If it's still not completed it will be tried again after the scheduled Poll.
        var i = 10
        while (i > 0 && commitsInProgress > 0) {
          LockSupport.parkNanos(10 * 1000)
          val pollTimeout = if (i == 1) 1L else 0L
          checkNoResult(tryPoll(pollTimeout))
          i -= 1
        }
      }
      else {
        processResult(partitionsToFetch, tryPoll(pollTimeout().toMillis))
      }
    }
    catch {
      case _: NoPollResult => // already handled, just proceed
    }
    finally wakeupTask.cancel()

    if (stopInProgress && commitsInProgress == 0) {
      context.stop(self)
    }
  }

  private def processResult(partitionsToFetch: Set[TopicPartition], rawResult: ConsumerRecords[K, V]): Unit = {
    if (!rawResult.isEmpty) {
      //check the we got only requested partitions and did not drop any messages
      val fetchedTps = rawResult.partitions().asScala
      if ((fetchedTps diff partitionsToFetch).nonEmpty)
        throw new scala.IllegalArgumentException(s"Unexpected records polled. Expected: $partitionsToFetch, " +
          s"result: ${rawResult.partitions()}, consumer assignment: ${consumer.assignment()}")

      //send messages to actors
      requests.foreach {
        case (ref, req) =>
          //gather all messages for ref
          val messages = req.topics.foldLeft[Iterator[ConsumerRecord[K, V]]](Iterator.empty) {
            case (acc, tp) =>
              val tpMessages = rawResult.records(tp).asScala.iterator
              if (acc.isEmpty) tpMessages
              else acc ++ tpMessages
          }
          if (messages.nonEmpty) {
            ref ! Messages(req.requestId, messages)
            requests -= ref
          }
      }
    }
  }
}
