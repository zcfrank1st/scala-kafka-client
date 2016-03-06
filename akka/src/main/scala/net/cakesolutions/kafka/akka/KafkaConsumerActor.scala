package net.cakesolutions.kafka.akka

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.actor._
import cakesolutions.kafka.KafkaConsumer
import com.typesafe.config.Config
import org.apache.kafka.clients.consumer.{ConsumerRecords, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.Deserializer
import scala.reflect.runtime.universe._
import scala.collection.JavaConversions._
import scala.concurrent.duration.{Duration, MILLISECONDS => Millis, _}
import scala.util.{Random, Failure, Success, Try}

object KafkaConsumerActor {

  /**
    * Actor API - Initiate consumption from Kafka or reset an already started stream.
    *
    * @param offsets Consumption starts from specified offsets or kafka default, depending on (auto.offset.reset) setting.
    */
  case class Subscribe(offsets: Option[Offsets] = None)

  /**
    * Actor API - Confirm receipt of previous records.  If Offets are provided, they are committed synchronously to Kafka.
    * If no offsets provided, no commit is made.
    *
    * @param offsets Some(offsets) if a commit to Kafka is required.
    */
  case class Confirm(offsets: Option[Offsets] = None)

  /**
    * Actor API - Unsubscribe from Kafka.
    */
  case object Unsubscribe

  /**
    * // Internal poll trigger
    * @param correlation unique correlation id
    * @param timeout Kafka blocking timeout.  Usually we don't want to block when polling the driver for new messages (default 0),
    *                but in some cases we want to apply a small timeout to reduce poll latency.  Specifically when we are
    *                consuming faster than we are pulling from Kafka, but there is still a backlog to get through.
    */
  private case class Poll(correlation: Int, timeout: Int = 0)

  //Receive State
  case class Unconfirmed[K, V](unconfirmed: Records[K, V], deliveryTime: LocalDateTime = LocalDateTime.now())

  //Receive State
  case class Buffered[K, V](unconfirmed: Records[K, V], deliveryTime: LocalDateTime = LocalDateTime.now(), buffered: Records[K, V])

  case class Offsets(offsetsMap: Map[TopicPartition, Long]) extends AnyVal {
    def get(topic: TopicPartition): Option[Long] = offsetsMap.get(topic)

    def forAllOffsets(that: Offsets)(f: (Long, Long) => Boolean): Boolean =
      offsetsMap.forall {
        case (topic, offset) => that.get(topic).forall(f(offset, _))
      }

    def toCommitMap: Map[TopicPartition, OffsetAndMetadata] =
      offsetsMap.mapValues(offset => new OffsetAndMetadata(offset))

    override def toString: String =
      offsetsMap
        .map { case (t, o) => s"$t: $o" }
        .mkString("Offsets(", ", ", ")")
  }

  case class Records[K: TypeTag, V: TypeTag](offsets: Offsets, records: ConsumerRecords[K, V]) {
    val keyTag = typeTag[K]
    val valueTag = typeTag[V]

    def hasType[K1: TypeTag, V2: TypeTag]: Boolean =
      typeTag[K1].tpe <:< keyTag.tpe &&
        typeTag[V2].tpe <:< valueTag.tpe

    def cast[K1: TypeTag, V2: TypeTag]: Option[Records[K1, V2]] =
      if (hasType[K1, V2]) Some(this.asInstanceOf[Records[K1, V2]])
      else None

    def values: Seq[V] = records.toList.map(_.value())
  }

  private val random = Random

  object Conf {

    /**
      * Configuration for KafkaConsumerActor from Config
      *
      * @param config
      * @return
      */
    def apply(config: Config): Conf = {
      val topics = config.getStringList("consumer.topics")

      val scheduleInterval = Duration(config.getDuration("schedule.interval", Millis), Millis)
      val unconfirmedTimeout = Duration(config.getDuration("unconfirmed.timeout", Millis), Millis)

      apply(topics.toList, scheduleInterval, unconfirmedTimeout)
    }
  }

  /**
    * Configuration for KafkaConsumerActor
    *
    * @param topics             List of topics to subscribe to.
    * @param scheduleInterval   Poll Latency.
    * @param unconfirmedTimeout Seconds before unconfirmed messages is considered for redelivery.
    */
  case class Conf(topics: List[String],
                  scheduleInterval: FiniteDuration = 1000.millis,
                  unconfirmedTimeout: FiniteDuration = 3.seconds) {
    def withConf(config: Config): Conf = {
      this.copy(topics = config.getStringList("consumer.topics").toList)
    }
  }

  /**
    * KafkaConsumer config and the consumer actors config all contained in a Typesafe Config.
    */
  def props[K: TypeTag, V: TypeTag](conf: Config,
                                    keyDeserializer: Deserializer[K],
                                    valueDeserializer: Deserializer[V],
                                    nextActor: ActorRef): Props = {
    Props(
      new KafkaConsumerActor[K, V](KafkaConsumer.Conf[K, V](conf, keyDeserializer, valueDeserializer),
        KafkaConsumerActor.Conf(conf),
        nextActor))
  }

  /**
    * Construct with configured KafkaConsumer and Actor configurations.
    */
  def props[K: TypeTag, V: TypeTag](consumerConf: KafkaConsumer.Conf[K, V],
                                    actorConf: KafkaConsumerActor.Conf,
                                    nextActor: ActorRef): Props = {
    Props(new KafkaConsumerActor[K, V](consumerConf, actorConf, nextActor))
  }
}

class KafkaConsumerActor[K: TypeTag, V: TypeTag](consumerConf: KafkaConsumer.Conf[K, V], actorConf: KafkaConsumerActor.Conf, nextActor: ActorRef)
  extends Actor with ActorLogging {

  import context.become
  import KafkaConsumerActor._

  private val consumer = KafkaConsumer[K, V](consumerConf)
  private val trackPartitions = TrackPartitions(consumer)

  //Tracks identifier of most recent poll request, so we can ignore old ones.
  private var pollCorrelation: Option[Int] = None

  //Handle on the poll schedule cancellable
  private var cancel: Option[Cancellable] = None

  override def receive = unsubscribed

  //Initial state
  def unsubscribed: Receive = {
    case Subscribe(offsets) =>
      log.info("Subscribing to topic(s): [{}]", actorConf.topics.mkString(", "))
      consumer.subscribe(actorConf.topics, trackPartitions)
      offsets.foreach(o => {
        log.info("Seeking to provided offsets")
        trackPartitions.offsets = o.offsetsMap
      })
      log.info("To Ready state")
      become(ready)
      pollImmediate(200)
  }

  //No unconfirmed or buffered messages
  def ready: Receive = {
    case Poll(correlation, timeout) if pollCorrelation.getOrElse(0) == correlation =>
      pollKafka(timeout) match {
        case Some(records) =>
          nextActor ! records
          log.info("To unconfirmed state")
          become(unconfirmed(Unconfirmed(records)))
          pollImmediate()

        case None =>
          schedulePoll()
      }
    case Unsubscribe =>
      become(unsubscribed)
  }

  // Unconfirmed message with client, buffer empty
  def unconfirmed(state: Unconfirmed[K, V]): Receive = {
    case Poll(correlation, _) if pollCorrelation.getOrElse(0) == correlation =>

      if (isConfirmationTimeout(state.deliveryTime)) {
        nextActor ! state.unconfirmed
      }

      pollKafka() match {
        case Some(records) =>
          nextActor ! records
          log.info("To Buffer Full state")
          become(bufferFull(Buffered(state.unconfirmed, state.deliveryTime, records)))
          schedulePoll()
        case None =>
          schedulePoll()
      }
    case Confirm(offsetsO) =>
      log.info(s"Records confirmed")
      offsetsO.foreach { offsets => commitOffsets(offsets) }
      log.info("To Ready state")
      become(ready)

      //immediate poll after confirm with block to reduce poll latency when backlog but processing is fast...
      pollImmediate(200)

    case Unsubscribe =>
      become(unsubscribed)
  }

  //Buffered message and unconfirmed message
  def bufferFull(state: Buffered[K, V]): Receive = {
    case Poll(correlation, _) if pollCorrelation.getOrElse(0) == correlation =>
      if (isConfirmationTimeout(state.deliveryTime)) {
        nextActor ! state.unconfirmed
      }

      log.info(s"Buffer is full. Not gonna poll.")
      schedulePoll()
    case Confirm(offsetsO) =>
      log.info(s"Records confirmed")
      offsetsO.foreach { offsets => commitOffsets(offsets) }
      nextActor ! state.buffered
      log.info("To unconfirmed state")
      become(unconfirmed(Unconfirmed(state.buffered)))
      pollImmediate()

    case Unsubscribe =>
      become(unsubscribed)
  }

  override def postStop(): Unit = {
    close()
  }

  /**
    * Attempt to get new records from Kafka
    *
    * @param timeout - specify a blocking poll timeout.  Default 0 for non blocking poll.
    * @return
    */
  private def pollKafka(timeout: Int = 0): Option[Records[K, V]] = {
    log.info("poll kafka {}", timeout)
    Try(consumer.poll(timeout)) match {
      case Success(rs) if rs.count() > 0 =>
        log.info("Records Received!")
        Some(Records(currentConsumerOffsets, rs))
      case Success(rs) =>
        None
      case Failure(_: WakeupException) =>
        log.warning("Poll was interrupted.")
        None
      case Failure(ex) =>
        log.error(ex, "Error occurred while attempting to poll Kafka!")
        None
    }
  }

  private def schedulePoll(): Unit = {
    log.info("Scheduling Poll, with timeout")

    cancelScheduledPoll()
    pollCorrelation = Some(random.nextInt())
    cancel = Some(
      context.system.scheduler.scheduleOnce(actorConf.scheduleInterval, self, Poll(pollCorrelation.get))(context.dispatcher)
    )
  }

  private def pollImmediate(timeout: Int = 0): Unit = {
    log.info("Poll immediate {}", timeout)

    cancelScheduledPoll()
    val correlation = random.nextInt()
    pollCorrelation = Some(correlation)
    self ! Poll(correlation, timeout)
  }

  private def cancelScheduledPoll() = {
    cancel.foreach(_.cancel())
  }

  private def currentConsumerOffsets: Offsets = {
    val offsetsMap = consumer.assignment()
      .map(p => p -> consumer.position(p))
      .toMap
    Offsets(offsetsMap)
  }

  /**
    * True if records unconfirmed for longer than unconfirmedTimeoutSecs
    *
    * @return
    */
  private def isConfirmationTimeout(deliveryTime: LocalDateTime): Boolean = {
    deliveryTime plus(actorConf.unconfirmedTimeout.toMillis, ChronoUnit.MILLIS) isBefore LocalDateTime.now()
  }

  private def commitOffsets(offsets: Offsets): Unit = {
    log.info("Committing offsets. {}", offsets)
    consumer.commitSync(offsets.toCommitMap)
  }

  override def unhandled(message: Any): Unit = {
    super.unhandled(message)
    log.warning("Unknown message: {}", message)
  }

  private def close(): Unit = {
    consumer.close()
  }
}
