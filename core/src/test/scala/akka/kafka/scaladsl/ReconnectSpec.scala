/*
 * Copyright (C) 2014 - 2016 Softwaremill <http://softwaremill.com>
 * Copyright (C) 2016 - 2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.kafka.scaladsl

import akka.Done
import akka.kafka._
import akka.kafka.test.Utils._
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete, Tcp}
import akka.stream.{KillSwitches, OverflowStrategy, UniqueKillSwitch}
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.apache.kafka.clients.producer.ProducerRecord

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class ReconnectSpec extends SpecBase(kafkaPort = KafkaPorts.ReconnectSpec) {

  val proxyPort = KafkaPorts.ReconnectSpecProxy

  def createKafkaConfig: EmbeddedKafkaConfig =
    EmbeddedKafkaConfig(
      kafkaPort,
      zooKeeperPort,
      Map(
        "offsets.topic.replication.factor" -> "1"
      ))

  "A Producer" must {

    "continue to work when there is another Kafka port available" in assertAllStagesStopped {
      val topic1 = createTopic(1)
      val group1 = createGroup(1)

      givenInitializedTopic(topic1)

      // start a TCP proxy forwarding to Kafka
      val (proxyBinding, proxyKillSwtich) = createProxy()
      Await.ready(proxyBinding, remainingOrDefault)

      val messagesProduced = 100
      val firstBatch = 10
      val messages = (1 to messagesProduced).map(_.toString)
      // start a producer flow with a queue as source
      val producer: SourceQueueWithComplete[String] = Source
        .queue[String](messagesProduced, OverflowStrategy.backpressure)
        .map(msg => new ProducerRecord(topic1, partition0, DefaultKey, msg))
        .to(Producer.plainSink(
          producerDefaults.withBootstrapServers(s"localhost:$proxyPort")))
        .run()

      def offerInOrder(msgs: Seq[String]): Future[_] =
        if (msgs.isEmpty) Future.successful(Done)
        else producer.offer(msgs.head).flatMap(_ => offerInOrder(msgs.tail))

      // put one batch into the stream
      offerInOrder(messages.take(firstBatch))

      // construct a consumer directly to Kafka and request messages and kill the proxy-Kafka connection
      val (_, probe) = createProbe(consumerDefaults.withGroupId(group1), topic1)
      probe.request(messagesProduced.toLong)
      probe.expectNextN(messages.take(firstBatch))
      val proxyConnection = proxyKillSwtich.futureValue
      proxyConnection.shutdown()

      probe.expectNoMessage(500.millis)

      offerInOrder(messages.drop(firstBatch))

      probe.expectNextN(messagesProduced.toLong - firstBatch) should be(messages.drop(firstBatch))

      // shut down
      producer.complete()
      probe.cancel()
    }

    "pick up again when the Kafka server comes back up" in pendingUntilFixed {
      assertAllStagesStopped {
        val topic1 = createTopic(1)
        val group1 = createGroup(1)

        givenInitializedTopic(topic1)

        val messagesProduced = 10
        val firstBatch = 2
        val messages = (1 to messagesProduced).map(_.toString)
        // start a producer flow with a queue as source
        val producer: SourceQueueWithComplete[String] = Source
          .queue[String](1, OverflowStrategy.backpressure)
          .map(msg => new ProducerRecord(topic1, partition0, DefaultKey, msg))
          .to(Producer.plainSink(producerDefaults))
          .run()

        def offerInOrder(msgs: Seq[String]): Future[_] =
          if (msgs.isEmpty) Future.successful(Done)
          else producer.offer(msgs.head).flatMap(_ => offerInOrder(msgs.tail))

        // put one batch into the stream
        offerInOrder(messages.take(firstBatch))

        // construct a consumer directly to Kafka and request messages and kill the proxy-Kafka connection
        val (_, probe) = createProbe(consumerDefaults.withGroupId(group1), topic1)
        probe.request(messagesProduced.toLong)
        probe.expectNextN(messages.take(firstBatch))
        EmbeddedKafka.stop()

        probe.expectNoMessage(500.millis)

        offerInOrder(messages.drop(firstBatch))
        // expect some radio silence
        probe.expectNoMessage(1.second)

        EmbeddedKafka.start()

        probe.request(messagesProduced.toLong)
        // TODO sometime no messages arrive, sometimes order is not kept
        probe.expectNextN(messagesProduced.toLong - firstBatch) should be(messages.drop(firstBatch))
        // shut down
        producer.complete()
        probe.cancel()
      }
    }

  }

  "A Consumer" must {

    "continue to work when there is another Kafka port available" in assertAllStagesStopped {
      val topic1 = createTopic(1)
      val group1 = createGroup(1)

      givenInitializedTopic(topic1)

      // produce messages directly to Kafka
      val messagesProduced = 100
      produce(topic1, 1 to messagesProduced)

      // create a TCP proxy and set up a consumer through it
      val (proxyBinding, proxyKillSwtich) = createProxy()
      Await.ready(proxyBinding, remainingOrDefault)
      val consumerSettings = consumerDefaults
        .withGroupId(group1)
        .withBootstrapServers(s"localhost:$proxyPort")
      val (control, probe) = createProbe(consumerSettings, topic1)

      // expect an element and kill the proxy
      probe.requestNext() should be("1")
      proxyKillSwtich.futureValue.shutdown()
      sleep(100.millis)

      probe.request(messagesProduced.toLong)
      probe.expectNextN((2 to messagesProduced).map(_.toString))

      // shut down
      control.shutdown().futureValue
    }

    "pick up again when the Kafka server comes back up" in assertAllStagesStopped {
      val topic1 = createTopic(1)
      val group1 = createGroup(1)

      givenInitializedTopic(topic1)

      // produce messages
      val messagesProduced = 100
      produce(topic1, 1 to messagesProduced)

      // create a consumer
      val (control, probe) = createProbe(consumerDefaults.withGroupId(group1), topic1)

      // expect an element and kill the Kafka instance
      probe.requestNext() should be("1")
      EmbeddedKafka.stop()
      sleep(1.second)

      // by now all messages have arrived in the consumer
      probe.request(messagesProduced.toLong - 1)
      probe.receiveWithin(100.millis) should be((2 to messagesProduced).map(_.toString))

      // expect silence
      probe.request(1)
      probe.expectNoMessage(1.second)

      // start a new Kafka server and produce another round
      EmbeddedKafka.start()
      sleep(1.second) // Got some messages dropped during startup
      produce(topic1, messagesProduced + 1 to messagesProduced * 2)

      probe.request(messagesProduced.toLong)
      probe.receiveWithin(5.second) should be((messagesProduced + 1 to messagesProduced * 2).map(_.toString))

      // shut down
      Await.ready(control.shutdown(), remainingOrDefault)
    }

  }

  /**
   * Create a proxy so it can be shut down.
   */
  def createProxy(): (Future[Tcp.ServerBinding], Future[UniqueKillSwitch]) = {
    // Create a proxy so it can be shut down
    val (proxyBinding, connection) =
      Tcp().bind("localhost", proxyPort).toMat(Sink.head)(Keep.both).run()
    val proxyKsFut = connection.map(
      _.handleWith(
        Tcp()
          .outgoingConnection("localhost", kafkaPort)
          .viaMat(KillSwitches.single)(Keep.right)
      )
    )
    (proxyBinding, proxyKsFut)
  }
}
