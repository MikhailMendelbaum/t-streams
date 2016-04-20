package agents.both.batch_insert.aerospike

import java.net.InetSocketAddress
import agents.both.batch_insert.BatchSizeTestVal
import com.aerospike.client.Host
import com.bwsw.tstreams.agents.consumer.Offsets.Oldest
import com.bwsw.tstreams.agents.consumer.{BasicConsumer, BasicConsumerOptions}
import com.bwsw.tstreams.agents.producer.InsertionType.BatchInsert
import com.bwsw.tstreams.agents.producer.{ProducerPolicies, BasicProducer, BasicProducerOptions}
import com.bwsw.tstreams.converter.{ArrayByteToStringConverter, StringToArrayByteConverter}
import com.bwsw.tstreams.coordination.Coordinator
import com.bwsw.tstreams.data.aerospike.{AerospikeStorageFactory, AerospikeStorageOptions}
import com.bwsw.tstreams.metadata.MetadataStorageFactory
import com.bwsw.tstreams.streams.BasicStream
import com.datastax.driver.core.Cluster
import org.redisson.{Redisson, Config}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import testutils.{RoundRobinPolicyCreator, LocalGeneratorCreator, CassandraHelper, RandomStringCreator}


class AManyBasicProducersStreamingInManyPartitionsAndConsumerTest extends FlatSpec with Matchers with BeforeAndAfterAll with BatchSizeTestVal{
  //creating keyspace, metadata
  def randomString: String = RandomStringCreator.randomAlphaString(10)
  val randomKeyspace = randomString
  val cluster = Cluster.builder().addContactPoint("localhost").build()
  val session = cluster.connect()
  CassandraHelper.createKeyspace(session, randomKeyspace)
  CassandraHelper.createMetadataTables(session, randomKeyspace)

  //metadata/data factories
  val metadataStorageFactory = new MetadataStorageFactory
  val storageFactory = new AerospikeStorageFactory

  //converters to convert usertype->storagetype; storagetype->usertype
  val arrayByteToStringConverter = new ArrayByteToStringConverter
  val stringToArrayByteConverter = new StringToArrayByteConverter

  //coordinator for coordinating producer/consumer
  val config = new Config()
  config.useSingleServer().setAddress("localhost:6379")
  val redissonClient = Redisson.create(config)
  val coordinator = new Coordinator("some_path", redissonClient)

  //aerospike storage options
  val hosts = List(
    new Host("localhost",3000),
    new Host("localhost",3001),
    new Host("localhost",3002),
    new Host("localhost",3003))
  val aerospikeOptions = new AerospikeStorageOptions("test", hosts)

  "Some amount of producers and one consumer" should "producers - send transactions in many partition" +
    " (each producer send each txn in only one partition without intersection " +
    " for ex. producer1 in partition1, producer2 in partition2, producer3 in partition3 etc...)," +
    " consumer - retrieve them all" in {
    val timeoutForWaiting = 60*5
    val totalPartitions = 4
    val totalTxn = 10
    val totalElementsInTxn = 3
    val producersAmount = 10
    val dataToSend = (for (part <- 0 until totalElementsInTxn) yield randomString).sorted

    val producers: List[BasicProducer[String, Array[Byte]]] =
      (0 until producersAmount)
        .toList
        .map(x=>getProducer(List(x%totalPartitions),totalPartitions))

    val producersThreads = producers.map(p =>
      new Thread(new Runnable {
        def run(){
          var i = 0
          while(i < totalTxn) {
            Thread.sleep(2000)
            val txn = p.newTransaction(ProducerPolicies.errorIfOpen)
            dataToSend.foreach(x => txn.send(x))
            txn.close()
            i+=1
          }
        }
      }))

    val streamInst = getStream(totalPartitions)

    val consumerOptions = new BasicConsumerOptions[Array[Byte], String](
      transactionsPreload = 10,
      dataPreload = 7,
      consumerKeepAliveInterval = 5,
      arrayByteToStringConverter,
      RoundRobinPolicyCreator.getRoundRobinPolicy(
        usedPartitions = (0 until totalPartitions).toList,
        stream = streamInst),
      Oldest,
      LocalGeneratorCreator.getGen(),
      useLastOffset = false)

    var checkVal = true

    val consumer = new BasicConsumer("test_consumer", streamInst, consumerOptions)

    val consumerThread = new Thread(
      new Runnable {
        Thread.sleep(3000)
        def run() = {
          var i = 0
          while(i < totalTxn*producersAmount) {
            val txn = consumer.getTransaction
            if (txn.isDefined){
              checkVal &= txn.get.getAll().sorted == dataToSend
              i+=1
            }
            Thread.sleep(200)
          }
        }
      })

    producersThreads.foreach(x=>x.start())
    consumerThread.start()
    consumerThread.join(timeoutForWaiting * 1000)
    producersThreads.foreach(x=>x.join(timeoutForWaiting * 1000))


    //assert that is nothing to read
    (0 until totalPartitions) foreach { _=>
      checkVal &= consumer.getTransaction.isEmpty
    }

    checkVal &= !consumerThread.isAlive
    producersThreads.foreach(x=> checkVal &= !x.isAlive)

    checkVal shouldEqual true
  }

  def getProducer(usedPartitions : List[Int], totalPartitions : Int) : BasicProducer[String,Array[Byte]] = {
    val stream = getStream(totalPartitions)

    val producerOptions = new BasicProducerOptions[String, Array[Byte]](
      transactionTTL = 6,
      transactionKeepAliveInterval = 2,
      producerKeepAliveInterval = 1,
      writePolicy = RoundRobinPolicyCreator.getRoundRobinPolicy(stream, usedPartitions),
      BatchInsert(batchSizeVal),
      LocalGeneratorCreator.getGen(),
      converter = stringToArrayByteConverter)

    val producer = new BasicProducer("test_producer1", stream, producerOptions)
    producer
  }

  def getStream(partitions : Int): BasicStream[Array[Byte]] = {
    //storage instances
    val metadataStorageInst = metadataStorageFactory.getInstance(
      cassandraHosts = List(new InetSocketAddress("localhost", 9042)),
      keyspace = randomKeyspace)
    val dataStorageInst = storageFactory.getInstance(aerospikeOptions)

    new BasicStream[Array[Byte]](
      name = "stream_name",
      partitions = partitions,
      metadataStorage = metadataStorageInst,
      dataStorage = dataStorageInst,
      coordinator = coordinator,
      ttl = 60 * 10,
      description = "some_description")
  }

  override def afterAll(): Unit = {
    session.execute(s"DROP KEYSPACE $randomKeyspace")
    session.close()
    cluster.close()
    metadataStorageFactory.closeFactory()
    storageFactory.closeFactory()
    redissonClient.shutdown()
  }
}