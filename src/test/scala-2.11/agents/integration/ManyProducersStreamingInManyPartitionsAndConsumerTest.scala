package agents.integration

import com.bwsw.tstreams.agents.consumer.Offset.Oldest
import com.bwsw.tstreams.agents.producer.{NewTransactionProducerPolicy, Producer}
import com.bwsw.tstreams.env.TSF_Dictionary
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import testutils._



class ManyProducersStreamingInManyPartitionsAndConsumerTest extends FlatSpec with Matchers with BeforeAndAfterAll with TestUtils {
  val timeoutForWaiting = 60 * 5
  val totalPartitions = 4
  val totalTxn = 10
  val totalElementsInTxn = 10
  val producersAmount = 5
  val dataToSend = (for (part <- 0 until totalElementsInTxn) yield randomString).sorted

  f.setProperty(TSF_Dictionary.Stream.NAME, "test_stream").
    setProperty(TSF_Dictionary.Stream.PARTITIONS, totalPartitions).
    setProperty(TSF_Dictionary.Stream.TTL, 60 * 10).
    setProperty(TSF_Dictionary.Coordination.CONNECTION_TIMEOUT, 7).
    setProperty(TSF_Dictionary.Coordination.TTL, 7).
    setProperty(TSF_Dictionary.Producer.TRANSPORT_TIMEOUT, 5).
    setProperty(TSF_Dictionary.Producer.Transaction.TTL, 6).
    setProperty(TSF_Dictionary.Producer.Transaction.KEEP_ALIVE, 2).
    setProperty(TSF_Dictionary.Consumer.TRANSACTION_PRELOAD, 10).
    setProperty(TSF_Dictionary.Consumer.DATA_PRELOAD, 10)

  val producers: List[Producer[String]] =
    (0 until producersAmount)
      .toList
      .map(x => getProducer(List(x % totalPartitions), totalPartitions))

  val producersThreads =
    producers.map(p =>
    new Thread(new Runnable {
      def run() {
        p.setAgentName("producer-" + Thread.currentThread().getName)
        var i = 0
        while (i < totalTxn) {
          val txn = p.newTransaction(NewTransactionProducerPolicy.ErrorIfOpened)
          dataToSend.foreach(x => txn.send(x))
          txn.checkpoint()
          i += 1
        }
      }
    }))


  var checkVal = true

  val consumer = f.getConsumer[String](
    name = "test_consumer",
    txnGenerator = LocalGeneratorCreator.getGen(),
    converter = arrayByteToStringConverter,
    partitions = (0 until totalPartitions).toSet,
    offset = Oldest,
    isUseLastOffset = false)

  consumer.start


  "Some amount of producers and one consumer" should "producers - send transactions in many partition" +
    " (each producer send each txn in only one partition without intersection " +
    " for ex. producer1 in partition1, producer2 in partition2, producer3 in partition3 etc...)," +
    " consumer - retrieve them all" in {

    val consumerThread = new Thread(
      new Runnable {
        def run() = {
          var i = 0
          val startTime = System.currentTimeMillis()
          while (i < totalTxn * producersAmount) {
            val txn = consumer.getTransaction
            if (txn.isDefined) {
              checkVal &= txn.get.getAll().sorted == dataToSend
              i += 1
            }
            if(System.currentTimeMillis() - startTime > 50000)
              {
                logger.info(s"I: ${i}, expected: ${totalTxn * producersAmount}")
                i = totalTxn * producersAmount
                checkVal = false
              }
          }
        }
      })

    logger.info("Created all producers")

    producersThreads.foreach(x => x.start())
    logger.info("Started all producers")
    consumerThread.start()
    logger.info("Started consumer")
    consumerThread.join(timeoutForWaiting * 1000)
    logger.info("Awaited consumer")
    producersThreads.foreach(x => x.join(timeoutForWaiting * 1000))
    logger.info("Awaited all producers")


    //assert that is nothing to read
    (0 until totalPartitions) foreach { _ =>
      checkVal &= consumer.getTransaction.isEmpty
    }

    checkVal &= !consumerThread.isAlive
    producersThreads.foreach(x => checkVal &= !x.isAlive)

    producers.foreach(_.stop())

    checkVal shouldEqual true
  }

  def getProducer(usedPartitions: List[Int], totalPartitions: Int): Producer[String] = {
    f.getProducer[String](
      name = "test_producer-" + Thread.currentThread().getName(),
      txnGenerator = LocalGeneratorCreator.getGen(),
      converter = stringToArrayByteConverter,
      partitions = usedPartitions.toSet,
      isLowPriority = false)
  }


  override def afterAll(): Unit = {
    onAfterAll()
  }
}