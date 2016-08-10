package com.bwsw.tstreams.agents.producer

import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, TimeUnit}

import com.bwsw.tstreams.agents.group.{Agent, CheckpointInfo, ProducerCheckpointInfo}
import com.bwsw.tstreams.agents.producer.NewTransactionProducerPolicy.ProducerPolicy
import com.bwsw.tstreams.common.{ResettableCountDownLatch, FirstFailLockableTaskExecutor, LockUtil, ThreadSignalSleepVar}
import com.bwsw.tstreams.coordination.clients.ProducerToSubscriberNotifier
import com.bwsw.tstreams.coordination.messages.state.{Message, TransactionStatus}
import com.bwsw.tstreams.coordination.producer.p2p.PeerAgent
import com.bwsw.tstreams.coordination.producer.transport.traits.Interaction
import com.bwsw.tstreams.metadata.MetadataStorage
import com.bwsw.tstreams.streams.TStream
import org.slf4j.LoggerFactory

import scala.util.control.Breaks._

/**
  * Basic producer class
  *
  * @param name            Producer name
  * @param stream          Stream for transaction sending
  * @param producerOptions This producer options
  * @tparam USERTYPE User data type
  */
class Producer[USERTYPE](val name: String,
                         val stream: TStream[Array[Byte]],
                         val producerOptions: Options[USERTYPE])
  extends Agent with Interaction {

  /**
    * agent name
    */
  override def getAgentName = name

  // shortkey
  val pcs = producerOptions.coordinationOptions

  var isStop = false

  // stores currently opened transactions per partition
  private val openTransactionsMap = new ConcurrentHashMap[Int, Transaction[USERTYPE]]()
  private val transactionReadynessMap = new ConcurrentHashMap[Int, ResettableCountDownLatch]()
  private val logger = LoggerFactory.getLogger(this.getClass)
  private val threadLock = new ReentrantLock(true)

  producerOptions.writePolicy.getUsedPartitions()
    .map(p => transactionReadynessMap.put(p, new ResettableCountDownLatch(0)))

  // amount of threads which will handle partitions in masters, etc
  val threadPoolSize: Int = {
    if (pcs.threadPoolAmount == -1)
      producerOptions.writePolicy.getUsedPartitions().size
    else
      pcs.threadPoolAmount
  }

  val txnLocks = new Array[ReentrantLock](threadPoolSize)

  (0 until threadPoolSize) foreach { idx =>
    txnLocks(idx) = new ReentrantLock()
  }

  stream.dataStorage.bind() //TODO: fix, probably deprecated

  logger.info(s"Start new Basic producer with name : $name, streamName : ${stream.getName}, streamPartitions : ${stream.getPartitions}")

  // this client is used to find new subscribers
  val subscriberNotifier = new ProducerToSubscriberNotifier(
    prefix = pcs.zkRootPath,
    streamName = stream.getName,
    usedPartitions = producerOptions.writePolicy.getUsedPartitions(),
    zkHosts = pcs.zkHosts,
    zkSessionTimeout = pcs.zkSessionTimeout,
    zkConnectionTimeout = pcs.zkConnectionTimeout)

  /**
    * P2P Agent for producers interaction
    * (getNewTxn uuid; publish openTxn event; publish closeTxn event)
    */
  override val p2pAgent: PeerAgent = new PeerAgent(agentAddress = pcs.agentAddress, zkHosts = pcs.zkHosts, zkRootPath = pcs.zkRootPath, zkSessionTimeout = pcs.zkSessionTimeout, zkConnectionTimeout = pcs.zkConnectionTimeout, producer = this, usedPartitions = producerOptions.writePolicy.getUsedPartitions(), isLowPriorityToBeMaster = pcs.isLowPriorityToBeMaster, transport = pcs.transport, poolSize = threadPoolSize)

  //used for managing new agents on stream

  {
    val zkStreamLock = subscriberNotifier.getStreamLock(stream.getName)
    zkStreamLock.lock()
    subscriberNotifier.init()
    zkStreamLock.unlock()
  }


  /**
    * Queue to figure out moment when transaction is going to close
    */
  private val shutdownKeepAliveThread = new ThreadSignalSleepVar[Boolean](1)
  private val txnKeepAliveThread = getTxnKeepAliveThread
  val backendActivityService = new FirstFailLockableTaskExecutor(s"Producer-worker-${name}")

  /**
    *
    */
  def getTxnKeepAliveThread: Thread = {
    val latch = new CountDownLatch(1)
    val txnKeepAliveThread = new Thread(new Runnable {
      override def run(): Unit = {
        Thread.currentThread().setName(s"Producer-${name}-KeepAlive")
        latch.countDown()
        logger.info(s"Producer ${name} - object is started, launched open transaction update thread")
        breakable {
          while (true) {
            val value: Boolean = shutdownKeepAliveThread.wait(producerOptions.transactionKeepAliveInterval * 1000)
            if (value) {
              logger.info(s"Producer ${name} - object either checkpointed or cancelled. Exit KeepAliveThread.")
              break()
            }
            backendActivityService.submit(new Runnable {
              override def run(): Unit = updateOpenedTransactions()
            }, Option(threadLock))
          }
        }
      }
    })
    txnKeepAliveThread.start()
    latch.await()
    txnKeepAliveThread
  }

  /**
    * Used to send update event to all opened transactions
    */
  private def updateOpenedTransactions() = {
    logger.debug(s"Producer ${name} - scheduled for long lasting transactions")
    val keys = openTransactionsMap.keys()
    val keyIterator = Iterator.continually((keys, keys.nextElement)).takeWhile(_._1.hasMoreElements).map(_._2)
    keyIterator.foreach(k =>
      Option(openTransactionsMap.getOrDefault(k, null)).map(t =>
        if(!t.isClosed) t.updateTxnKeepAliveState()))
  }

  /**
    * @param policy        Policy for previous transaction on concrete partition
    * @param nextPartition Next partition to use for transaction (default -1 which mean that write policy will be used)
    * @return BasicProducerTransaction instance
    */
  def newTransaction(policy: ProducerPolicy, nextPartition: Int = -1): Transaction[USERTYPE] = {
    if (isStop)
      throw new IllegalStateException(s"Producer ${this.name} is already stopped. Unable to get new transaction.")

    val partition = {
      if (nextPartition == -1)
        producerOptions.writePolicy.getNextPartition
      else
        nextPartition
    }

    if (!(partition >= 0 && partition < stream.getPartitions))
      throw new IllegalArgumentException(s"Producer ${name} - invalid partition")

    val partOpt = Option(openTransactionsMap.getOrDefault(partition, null))
    if (partOpt.isDefined) {
      partOpt.get.awaitMaterialized()
    }

    transactionReadynessMap.get(partition).setValue(1)
    var action: () => Unit = null
    if (partOpt.isDefined) {
      if (!partOpt.get.isClosed) {
        policy match {
          case NewTransactionProducerPolicy.CheckpointIfOpened =>
            action = () => partOpt.get.checkpoint()

          case NewTransactionProducerPolicy.CancelIfOpened =>
            action = () => partOpt.get.cancel()

          case NewTransactionProducerPolicy.ErrorIfOpened =>
            throw new IllegalStateException(s"Producer ${name} - previous transaction was not closed")
        }
      }
    }
    val txnUUID = p2pAgent.generateNewTransaction(partition)
    logger.debug(s"[NEW_TRANSACTION PARTITION_$partition] uuid=${txnUUID.timestamp()}")
    val txn = new Transaction[USERTYPE](txnLocks(partition % threadPoolSize), partition, txnUUID, this)
    openTransactionsMap.put(partition, txn)
    transactionReadynessMap.get(partition).countDown
    if(action != null) action()
    txn
  }

  /**
    * Return transaction for specific partition if there is opened one.
    *
    * @param partition Partition from which transaction will be retrieved
    * @return Transaction reference if it exist and is opened
    */
  def getOpenedTransactionForPartition(partition: Int): Option[Transaction[USERTYPE]] = {
    if (!(partition >= 0 && partition < stream.getPartitions))
      throw new IllegalArgumentException(s"Producer ${name} - invalid partition")
    val partOpt = Option(openTransactionsMap.getOrDefault(partition, null))
    val txnOpt =
      if (partOpt.isDefined) {
        if (partOpt.get.isClosed) {
          None
        }
        partOpt
      }
      else
        None
    txnOpt
  }

  /**
    * Checkpoint all opened transactions (not atomic). For atomic use CheckpointGroup.
    */
  def checkpoint(isAsynchronous: Boolean = false): Unit = {
    val keys = openTransactionsMap.keys()
    val keyIterator = Iterator.continually((keys, keys.nextElement)).takeWhile(_._1.hasMoreElements).map(_._2)
    keyIterator.foreach(k =>
      Option(openTransactionsMap.getOrDefault(k, null)).map(t =>
        if(!t.isClosed) t.checkpoint(isAsynchronous)))
  }

  /**
    * Cancel all opened transactions (not atomic). For atomic use CheckpointGroup.
    */
  def cancel(): Unit = {
    val keys = openTransactionsMap.keys()
    val keyIterator = Iterator.continually((keys, keys.nextElement)).takeWhile(_._1.hasMoreElements).map(_._2)
    keyIterator.foreach(k =>
      Option(openTransactionsMap.getOrDefault(k, null)).map(t =>
        if(!t.isClosed) t.cancel()))
  }


  /**
    * Info to commit
    */
  override def getCheckpointInfoAndClear(): List[CheckpointInfo] = {
    LockUtil.withLockOrDieDo[List[CheckpointInfo]](threadLock, (100, TimeUnit.SECONDS), Some(logger), () => {
      val keys = openTransactionsMap.keys()
      val keyIterator = Iterator.continually((keys, keys.nextElement)).takeWhile(_._1.hasMoreElements).map(_._2)

      var checkpointInfo = List[CheckpointInfo]()

      keyIterator.map(part =>
        Option(openTransactionsMap.getOrDefault(part, null)).map(t =>
          if (!t.isClosed) checkpointInfo = t.getTransactionInfo() :: checkpointInfo))

      openTransactionsMap.clear()
      checkpointInfo
    })
  }

  /**
    * @return Metadata storage link for concrete agent
    */
  override def getMetadataRef(): MetadataStorage =
    stream.metadataStorage

  /**
    *
    * @return
    */
  def getNewTxnUUIDLocal(): UUID = {
    val transactionUuid = producerOptions.txnGenerator.getTimeUUID()
    transactionUuid
  }

  /**
    * Method to implement for concrete producer [[PeerAgent]] method
    * Need only if this producer is master
    *
    * @return UUID
    */
  override def openTxnLocal(txnUUID: UUID, partition: Int, onComplete: () => Unit): Unit = {
    stream.metadataStorage.commitEntity.commit(
      streamName = stream.getName,
      partition = partition,
      transaction = txnUUID,
      totalCnt = -1,
      ttl = producerOptions.transactionTTL)

    val msg = Message(
      txnUuid = txnUUID,
      ttl = producerOptions.transactionTTL,
      status = TransactionStatus.opened,
      partition = partition)

    logger.debug(s"Producer ${name} - [GET_LOCAL_TXN PRODUCER] update with msg partition=$partition uuid=${txnUUID.timestamp()} opened")
    subscriberNotifier.publish(msg, onComplete)
  }


  /**
    * Stop this agent
    */
  def stop() = {

    LockUtil.withLockOrDieDo[Unit](threadLock, (100, TimeUnit.SECONDS), Some(logger), () => {
      if (isStop)
        throw new IllegalStateException(s"Producer ${this.name} is already stopped. Duplicate action.")
      isStop = true })

    // stop update state of all open transactions
    shutdownKeepAliveThread.signal(true)
    txnKeepAliveThread.join()
    // stop executor
    backendActivityService.shutdownSafe()
    // stop provide master features to public
    p2pAgent.stop()
    // stop function which works with subscribers
    subscriberNotifier.stop()
  }

  /**
    * Agent lock on any actions which has to do with checkpoint
    */
  override def getThreadLock(): ReentrantLock = threadLock


  def materialize(msg: Message) = {
    logger.debug(s"Start handling MaterializeRequest at partition: ${msg.partition}")
    transactionReadynessMap.get(msg.partition).await()
    assert(getOpenedTransactionForPartition(msg.partition).isDefined)
    logger.debug(s"In Map TXN: ${getOpenedTransactionForPartition(msg.partition).get.getTxnUUID.toString}\nIn Request TXN: ${msg.txnUuid}")
    assert(getOpenedTransactionForPartition(msg.partition).get.getTxnUUID == msg.txnUuid)
    assert(msg.status == TransactionStatus.materialize)
    getOpenedTransactionForPartition(msg.partition).get.makeMaterialized()
    logger.debug("End handling MaterializeRequest")
  }
}