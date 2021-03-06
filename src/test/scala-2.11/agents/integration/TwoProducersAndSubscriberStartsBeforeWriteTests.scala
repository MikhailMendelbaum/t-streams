package agents.integration

import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}

import com.bwsw.tstreams.agents.consumer.Offset.Newest
import com.bwsw.tstreams.agents.consumer.TransactionOperator
import com.bwsw.tstreams.agents.consumer.subscriber.Callback
import com.bwsw.tstreams.agents.producer.NewTransactionProducerPolicy
import com.bwsw.tstreams.env.TSF_Dictionary
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import testutils.{LocalGeneratorCreator, TestUtils}

import scala.collection.mutable.ListBuffer

/**
  * Created by Ivan Kudryavtsev on 07.09.16.
  */
class TwoProducersAndSubscriberStartsBeforeWriteTests extends FlatSpec with Matchers with BeforeAndAfterAll with TestUtils  {

  f.setProperty(TSF_Dictionary.Stream.NAME, "test_stream").
    setProperty(TSF_Dictionary.Stream.PARTITIONS, 3).
    setProperty(TSF_Dictionary.Stream.TTL, 60 * 10).
    setProperty(TSF_Dictionary.Coordination.CONNECTION_TIMEOUT, 7).
    setProperty(TSF_Dictionary.Coordination.TTL, 7).
    setProperty(TSF_Dictionary.Producer.TRANSPORT_TIMEOUT, 5).
    setProperty(TSF_Dictionary.Producer.Transaction.TTL, 3).
    setProperty(TSF_Dictionary.Producer.Transaction.KEEP_ALIVE, 1).
    setProperty(TSF_Dictionary.Consumer.TRANSACTION_PRELOAD, 10).
    setProperty(TSF_Dictionary.Consumer.DATA_PRELOAD, 10)

  val COUNT=1000

  it should s"Two producers send ${COUNT} transactions each, subscriber receives ${2 * COUNT} when started after." in {

    val bp = ListBuffer[UUID]()
    val bs = ListBuffer[UUID]()

    val lp2 = new CountDownLatch(1)
    val  ls = new  CountDownLatch(1)

    val producer1 = f.getProducer[String](
      name = "test_producer1",
      txnGenerator = LocalGeneratorCreator.getGen(),
      converter = stringToArrayByteConverter,
      partitions = Set(0),
      isLowPriority = false)


    val producer2 = f.getProducer[String](
      name = "test_producer2",
      txnGenerator = LocalGeneratorCreator.getGen(),
      converter = stringToArrayByteConverter,
      partitions = Set(0),
      isLowPriority = false)

    val s = f.getSubscriber[String](name = "ss+2",
      txnGenerator = LocalGeneratorCreator.getGen(),
      converter = arrayByteToStringConverter,
      partitions = Set(0),
      offset = Newest,
      isUseLastOffset = true,
      callback = new Callback[String] {
        override def onEvent(consumer: TransactionOperator[String], partition: Int, uuid: UUID, count: Int): Unit = this.synchronized {
          bs.append(uuid)
          if (bs.size == 2 * COUNT) {
            ls.countDown()
          }
        }
      })

    val t1 = new Thread(new Runnable {
      override def run(): Unit = {
        logger.info(s"Producer-1 is master of partition: ${producer1.isMeAMasterOfPartition(0)}")
        for (i <- 0 until COUNT) {
          val t = producer1.newTransaction(policy = NewTransactionProducerPolicy.CheckpointIfOpened)
          bp.append(t.getTransactionUUID())
          lp2.countDown()
          t.send("test")
          t.checkpoint()
        }
      }
    })
    val t2 = new Thread(new Runnable {
      override def run(): Unit = {
        logger.info(s"Producer-2 is master of partition: ${producer2.isMeAMasterOfPartition(0)}")
        for (i <- 0 until COUNT) {
          lp2.await()
          val t = producer2.newTransaction(policy = NewTransactionProducerPolicy.CheckpointIfOpened)
          bp.append(t.getTransactionUUID())
          t.send("test")
          t.checkpoint()
        }
      }
    })
    s.start()

    t1.start()
    t2.start()

    t1.join()
    t2.join()

    ls.await(10, TimeUnit.SECONDS)
    producer1.stop()
    producer2.stop()
    s.stop()
    bs.size shouldBe 2 * COUNT
  }



  override def afterAll(): Unit = {
    onAfterAll()
  }
}
