package agents.integration

import com.bwsw.tstreams.agents.consumer.Offset.Oldest
import com.bwsw.tstreams.agents.producer.{NewTransactionProducerPolicy, Producer}
import com.bwsw.tstreams.env.TSF_Dictionary
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import testutils._


class ManyProducersStreamingInManyPartitionsAndConsumerWithCheckpointsTest extends FlatSpec
  with Matchers with BeforeAndAfterAll with TestUtils {

  val totalPartitions = 20
  val totalTxn = 1000
  val totalElementsInTxn = 3
  val producersAmount = 20
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


  "Some amount of producers and one consumer" should "producers - send transactions in many partition" +
    " (each producer send each txn in only one partition without intersection " +
    " for ex. producer1 in partition1, producer2 in partition2, producer3 in partition3 etc...) " +
    " consumer - retrieve them all with reinitialization every 30 transactions" in {

    val producers: List[Producer[String]] =
      (0 until producersAmount)
        .toList
        .map(x => getProducer(List(x % totalPartitions), totalPartitions))

    val producersThreads = producers.map(p =>
      new Thread(new Runnable {
        def run() {
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
      isUseLastOffset = true)

    val consumer2 = f.getConsumer[String](
      name = "test_consumer",
      txnGenerator = LocalGeneratorCreator.getGen(),
      converter = arrayByteToStringConverter,
      partitions = (0 until totalPartitions).toSet,
      offset = Oldest,
      isUseLastOffset = true)

    consumer.start()

    val consumerThread = new Thread(
      new Runnable {

        def run() = {
          var i = 0
          while (i < totalTxn * producersAmount) {
            if (i == 30) {
              consumer.checkpoint()
              consumer2.start
            }

            val txn = if (i<30)
              consumer.getTransaction
            else
              consumer2.getTransaction

            if (txn.isDefined) {
              txn.get.getAll().sorted shouldBe dataToSend
              i += 1
            } else {
              Thread.sleep(100)
            }
          }
        }
      })

    producersThreads.foreach(x => x.start())
    consumerThread.start()
    consumerThread.join()
    producersThreads.foreach(x => x.join())

    //assert that is nothing to read
    (0 until totalPartitions) foreach { _ =>
      checkVal &= consumer2.getTransaction.isEmpty
    }

    producers.foreach(_.stop())
  }

  def getProducer(usedPartitions: List[Int], totalPartitions: Int): Producer[String] = {
    f.getProducer[String](
      name = "test_producer",
      txnGenerator = LocalGeneratorCreator.getGen(),
      converter = stringToArrayByteConverter,
      partitions = usedPartitions.toSet,
      isLowPriority = false)
  }

  override def afterAll(): Unit = {
    onAfterAll()
  }
}
