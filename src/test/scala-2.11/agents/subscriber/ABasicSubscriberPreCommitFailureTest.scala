package agents.subscriber

import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.locks.ReentrantLock

import com.bwsw.tstreams.agents.consumer.Offsets.Oldest
import com.bwsw.tstreams.agents.consumer.subscriber.{BasicSubscriberCallback, BasicSubscribingConsumer}
import com.bwsw.tstreams.agents.consumer.{BasicConsumerOptions, SubscriberCoordinationOptions}
import com.bwsw.tstreams.agents.producer.InsertionType.BatchInsert
import com.bwsw.tstreams.agents.producer.{BasicProducer, BasicProducerOptions, ProducerCoordinationOptions, ProducerPolicies}
import com.bwsw.tstreams.coordination.transactions.transport.impl.TcpTransport
import com.bwsw.tstreams.debug.GlobalHooks
import com.bwsw.tstreams.env.TSF_Dictionary
import com.bwsw.tstreams.streams.BasicStream
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import testutils.{LocalGeneratorCreator, RoundRobinPolicyCreator, TestUtils}

//TODO refactoring
class ABasicSubscriberPreCommitFailureTest extends FlatSpec with Matchers
  with BeforeAndAfterAll with TestUtils {

  System.setProperty("DEBUG", "true")
  GlobalHooks.addHook("PreCommitFailure", () => throw new RuntimeException)

  f.setProperty(TSF_Dictionary.Stream.name,"test_stream").
    setProperty(TSF_Dictionary.Stream.partitions,3).
    setProperty(TSF_Dictionary.Stream.ttl, 60 * 10).
    setProperty(TSF_Dictionary.Coordination.connection_timeout, 7).
    setProperty(TSF_Dictionary.Coordination.ttl, 7).
    setProperty(TSF_Dictionary.Producer.master_timeout, 5).
    setProperty(TSF_Dictionary.Producer.Transaction.ttl, 3).
    setProperty(TSF_Dictionary.Producer.Transaction.keep_alive, 1).
    setProperty(TSF_Dictionary.Consumer.transaction_preload, 10).
    setProperty(TSF_Dictionary.Consumer.data_preload, 10)

  val lock = new ReentrantLock()
  var acc = 0
  val totalTxns = 30
  val dataInTxn = 10
  val data = randomString
  val l1 = new CountDownLatch(1)
  val l2 = new CountDownLatch(1)
  val l3 = new CountDownLatch(1)


  val producer = f.getProducer[String](
    name = "test_producer",
    txnGenerator = LocalGeneratorCreator.getGen(),
    converter = stringToArrayByteConverter,
    partitions = List(0,1,2),
    isLowPriority = false)

  val callback = new BasicSubscriberCallback[String] {
    override def onEvent(
                          subscriber: BasicSubscribingConsumer[String],
                          partition: Int,
                          transactionUuid: UUID): Unit = {
      lock.lock()

      acc += 1

      logger.info("TXN is: " + transactionUuid.toString)

      subscriber.setLocalOffset(partition, transactionUuid)
      subscriber.checkpoint()
      lock.unlock()
    }
  }

  val s1 = f.getSubscriber[String](
    name = "test_subscriber",
    txnGenerator = LocalGeneratorCreator.getGen(),
    converter = arrayByteToStringConverter,
    partitions = List(0,1,2),
    callback = callback,
    offset = Oldest,
    isUseLastOffset = true)

  val s2 = f.getSubscriber[String](
    name = "test_subscriber",
    txnGenerator = LocalGeneratorCreator.getGen(),
    converter = arrayByteToStringConverter,
    partitions = List(0,1,2),
    callback = callback,
    offset = Oldest,
    isUseLastOffset = true)

  "subscribe consumer" should "retrieve all sent messages" in {

    s1.start()
    sendTxnsAndWait(totalTxns, dataInTxn, data)
    // TODO FIX incorrect shutdown #31
    s1.stop()

    s2.start()
    sendTxnsAndWait(totalTxns, dataInTxn, data)
    // TODO FIX incorrect shutdown #31
    s2.stop()

    acc shouldEqual 0
  }

  def sendTxnsAndWait(totalTxns: Int, dataInTxn: Int, data: String) = {
    (0 until totalTxns) foreach { x =>
      val txn = producer.newTransaction(ProducerPolicies.errorIfOpened)
      (0 until dataInTxn) foreach { _ =>
        txn.send(data)
      }
      try {
        txn.checkpoint()
      } catch {
        case e: RuntimeException =>
      }
    }
  }

  override def afterAll(): Unit = {
    System.clearProperty("DEBUG")
    GlobalHooks.clear()
    producer.stop()
    onAfterAll()
  }
}
