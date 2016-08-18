package com.bwsw.tstreams.coordination.producer.p2p

import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.{CountDownLatch, TimeUnit}

import com.bwsw.tstreams.agents.producer.Producer
import com.bwsw.tstreams.common.ProtocolMessageSerializer.ProtocolMessageSerializerException
import com.bwsw.tstreams.common.{FirstFailLockableTaskExecutor, LockUtil, ProtocolMessageSerializer, ZookeeperDLMService}
import com.bwsw.tstreams.coordination.messages.master._
import com.bwsw.tstreams.coordination.messages.state.Message
import com.bwsw.tstreams.coordination.producer.transport.impl.TcpTransport
import io.netty.channel.Channel
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.util.Random

/**
  *
  */
object PeerAgent {
  /**
    * For making low priority masters
    *
    */
  val LOW_PRIORITY_PENALTY  = 1000 * 1000
  /**
    * How long to sleep during master elections actions
    */
  val RETRY_SLEEP_TIME      = 1000
  /**
    * Generic logger object
    */
  val logger                = LoggerFactory.getLogger(this.getClass)

}

/**
 * Agent for providing peer to peer interaction between Producers
 *
 * @param zkService
 * @param zkRetriesAmount
 * @param producer Producer reference
 * @param usedPartitions List of used producer partitions
 * @param isLowPriorityToBeMaster Flag which indicate to have low priority to be master
 * @param transport Transport to provide interaction
 */
class PeerAgent(masterManager: PartitionMasterManager, zkService: ZookeeperDLMService, zkRetriesAmount: Int, producer: Producer[_], usedPartitions: List[Int], isLowPriorityToBeMaster: Boolean, transport: TcpTransport, poolSize: Int) {
  val myIPAddress: String = producer.producerOptions.coordinationOptions.transport.getIpAddress()
  /**
    * locks
    */
  private val externalAccessLock = new ReentrantLock(true)
  private val lockManagingMaster = new ReentrantLock(true)

  /**
    * Job Executors
    */
  private val newTxnExecutors           = mutable.Map[Int, FirstFailLockableTaskExecutor]()
  private val publishExecutors          = mutable.Map[Int, FirstFailLockableTaskExecutor]()
  private val materializationExecutors  = mutable.Map[Int, FirstFailLockableTaskExecutor]()
  private val masterRequestsExecutor    = new FirstFailLockableTaskExecutor(s"${producer.name}-masterRequests")
  private val cassandraAsyncExecutor    = new FirstFailLockableTaskExecutor(s"${producer.name}-cassandraAsyncRequests")

  private val streamName = producer.stream.getName
  private val isRunning = new AtomicBoolean(true)
  private var zkConnectionValidator: Thread = null

  def getAgentAddress()           = myIPAddress
  def getTransport()              = transport
  def getUsedPartitions()         = usedPartitions
  def getProducer()               = producer
  def getCassandraAsyncExecutor() = cassandraAsyncExecutor
  def getMasterManager()          = masterManager

  /**
    * this ID is used to track sequential transactions from the same master
    */
  private val uniqueAgentId = {
    val v = Random.nextInt()
    if (v<0) -v else v
  }

  /**
    * this ID map is used to track sequential transactions on subscribers
    */
  private val sequentialIds = mutable.Map[Int, AtomicLong]()

  PeerAgent.logger.info(s"[INIT] Start initialize agent with address: {$myIPAddress}")
  PeerAgent.logger.info(s"[INIT] Stream: {$streamName}, partitions: [${usedPartitions.mkString(",")}]")
  PeerAgent.logger.info(s"[INIT] Master Unique random ID: $uniqueAgentId")

  transport.start((s: Channel, m: String) => this.handleMessage(s,m))

  startSessionKeepAliveThread()

  (0 until poolSize) foreach { x =>
    newTxnExecutors(x) = new FirstFailLockableTaskExecutor(s"${producer.name}-newTxn-${x}")
    materializationExecutors(x) = new FirstFailLockableTaskExecutor(s"${producer.name}-materialization-${x}")
    publishExecutors(x) = new FirstFailLockableTaskExecutor(s"${producer.name}-publish-${x}")
  }

  private val partitionsToExecutors = usedPartitions
    .zipWithIndex
    .map { case (partition, execNum) => (partition, execNum % poolSize) }
    .toMap



  // fill initial sequential counters
  usedPartitions foreach { p =>sequentialIds += (p -> new AtomicLong(0)) }

  masterManager.bootstrap(isLowPriorityToBeMaster, uniqueAgentId)

  usedPartitions foreach { p =>
    updateMaster(p, init = true)
  }

  /**
    * Helper method for new master voting
    *
    * @param partition New master partition
    * @param retries   Retries to try to set new master
    * @return Selected master address
    */
  private def electPartitionMasterInternal(partition: Int, retries: Int = zkRetriesAmount): String = {
    if (PeerAgent.logger.isDebugEnabled)
    {
      PeerAgent.logger.debug(s"[VOTING] Start voting new agent on address: {$myIPAddress} on stream: {$streamName}, partition:{$partition}")
    }
    val master = masterManager.getCurrentMaster(partition)
    master.fold {
      val bestCandidate = masterManager.getBestMasterCandidate(partition)
      transport.setMasterRequest(bestCandidate, partition) match {
        case null =>
          if (retries == 0)
            throw new IllegalStateException("agent is not responded")
          //assume that if master is not responded it will be deleted by zk
          Thread.sleep(PeerAgent.RETRY_SLEEP_TIME)
          electPartitionMasterInternal(partition, retries - 1)

        case SetMasterResponse(_, _, p) =>
          assert(p == partition)
          bestCandidate

        case EmptyResponse(_, _, p) =>
          assert(p == partition)
          bestCandidate
      }
    }(master => master.agentAddress)
  }

  /**
    * Voting new master for concrete partition
    *
    * @param partition Partition to vote new master
    * @return New master Address
    */
  private def electPartitionMaster(partition: Int): String =
    masterManager.withElectionLockDo(partition, () => electPartitionMasterInternal(partition))

  /**
    * Updating master on concrete partition
    *
    * @param partition Partition to update master
    * @param init      If flag true master will be reselected anyway else old master can stay
    * @param retries   Retries to try to interact with master
    */
  def updateMaster(partition: Int, init: Boolean, retries: Int = zkRetriesAmount): Unit = {
    if (PeerAgent.logger.isDebugEnabled)
    {
      PeerAgent.logger.debug(s"[UPDATER] Updating master with init: {$init} on agent: {$myIPAddress} on stream: {$streamName}, partition: {$partition} with retry=$retries.")
    }
    val masterOpt = masterManager.getCurrentMaster(partition)
    masterOpt.fold[Unit](electPartitionMaster(partition)) { master =>
      if (init) {
        val ans = transport.deleteMasterRequest(master.agentAddress, partition)
        ans match {
          case null =>
            if (retries == 0)
              throw new IllegalStateException(s"Agent didn't responded to me.")
            //assume that if master is not responded it will be deleted by zk
            Thread.sleep(PeerAgent.RETRY_SLEEP_TIME)
            updateMaster(partition, init, retries - 1)

          case EmptyResponse(_, _, p) =>
            assert(p == partition)
            Thread.sleep(PeerAgent.RETRY_SLEEP_TIME)
            updateMaster(partition, init, zkRetriesAmount)

          case DeleteMasterResponse(_, _, p) =>
            assert(p == partition)
            val newMaster = electPartitionMaster(partition)
            if (PeerAgent.logger.isDebugEnabled)
            {
              PeerAgent.logger.debug(s"[UPDATER] Finish updating master with init: {$init} on agent: {$myIPAddress} on stream: {$streamName}, partition: {$partition} with retry=$retries; revoted master: {$newMaster}.")
            }
            masterManager.putPartitionMasterLocally(partition, newMaster)
        }
      } else {
        transport.pingRequest(master.agentAddress, partition) match {
          case null =>
            if (retries == 0)
              throw new IllegalStateException(s"Agent didn't responded to me.")
            //assume that if master is not responded it will be deleted by zk
            Thread.sleep(PeerAgent.RETRY_SLEEP_TIME)
            updateMaster(partition, init, retries - 1)

          case EmptyResponse(_, _, p) =>
            assert(p == partition)
            Thread.sleep(PeerAgent.RETRY_SLEEP_TIME)
            updateMaster(partition, init, zkRetriesAmount)

          case PingResponse(_, _, p) =>
            assert(p == partition)
            if (PeerAgent.logger.isDebugEnabled)
            {
              PeerAgent.logger.debug(s"[UPDATER] Finish updating master with init: {$init} on agent: {$myIPAddress} on stream: {$streamName}, partition: {$partition} with retry=$retries; old master: {$master} is alive now.")
            }
            masterManager.putPartitionMasterLocally(partition, master.agentAddress)
        }
      }
    }
  }



  /**
    * Starting validate zk connection (if it will be down, exception will be thrown)
    * Probably may be removed
    */
  private def startSessionKeepAliveThread() = {
    val latch = new CountDownLatch(1)
    val agent = this
    zkConnectionValidator = new Thread(new Runnable {
      override def run(): Unit = {
        latch.countDown()
        var retries = 0
        try {
          while (isRunning.get()) {
            if (!zkService.isZkConnected)
              retries += 1
            else
              retries = 0
            if (retries >= 3) {
              PeerAgent.logger.error(s"Agent ${agent.getProducer.name} ${myIPAddress} - Zk connection Lost. Immediately shutdown.")
              System.exit(1)
            }
            Thread.sleep(PeerAgent.RETRY_SLEEP_TIME)
          }
        } catch {
          case e: Exception =>
            PeerAgent.logger.error(s"Agent ${agent.getProducer.name} ${myIPAddress} - Zk connection Lost. Immediately shutdown.")
            System.exit(1)
        }
      }
    })
    zkConnectionValidator.start()
    latch.await()
  }

  /**
    * Retrieve new transaction from agent
    *
    * @param partition Transaction partition
    * @return Transaction UUID
    */
  def generateNewTransaction(partition: Int): UUID = {
    LockUtil.withLockOrDieDo[UUID](externalAccessLock, (100, TimeUnit.SECONDS), Some(PeerAgent.logger), () => {
      val master = masterManager.getPartitionMasterLocally(partition, null)
      if (PeerAgent.logger.isDebugEnabled)
      {
        PeerAgent.logger.debug(s"[GETTXN] Start retrieve txn for agent with address: {$myIPAddress}, stream: {$streamName}, partition: {$partition} from [MASTER: {$master}].")
      }

      val res =
        if (master != null) {
          val txnResponse = transport.transactionRequest(master, partition)
          txnResponse match {
            case null =>
              updateMaster(partition, init = false)
              generateNewTransaction(partition)

            case EmptyResponse(snd, rcv, p) =>
              assert(p == partition)
              updateMaster(partition, init = false)
              generateNewTransaction(partition)

            case TransactionResponse(snd, rcv, uuid, p) =>
              assert(p == partition)
              if (PeerAgent.logger.isDebugEnabled)
              {
                PeerAgent.logger.debug(s"[GETTXN] Finish retrieve txn for agent with address: {$myIPAddress}, stream: {$streamName}, partition: {$partition} with timeuuid: {${uuid.timestamp()}} from [MASTER: {$master}]s")
              }
              uuid
          }
        } else {
          updateMaster(partition, init = false)
          generateNewTransaction(partition)
        }
      res
    })
  }

  def notifyMaterialize(msg: Message, to: String): Unit = {
    if (PeerAgent.logger.isDebugEnabled)
    {
      PeerAgent.logger.debug(s"[MATERIALIZE] Send materialize request address\nMe: {$myIPAddress}\nTXN owner: ${to}\nStream: ${streamName}\npartition: ${msg.partition}\nTXN: ${msg.txnUuid}")
    }
    transport.materializeRequest(to, msg)
  }

  //TODO remove after complex testing
  def publish(msg: Message): Unit = {
    LockUtil.withLockOrDieDo[Unit](externalAccessLock, (100, TimeUnit.SECONDS), Some(PeerAgent.logger), () => {
      val master = masterManager.getPartitionMasterLocally(msg.partition, null)
      if (PeerAgent.logger.isDebugEnabled)
        PeerAgent.logger.debug(s"[PUBLISH] SEND PTM:{$msg} to [MASTER:{$master}] from agent:{$myIPAddress}," +
          s"stream:{$streamName}")
      if (master != null) {
        transport.publishRequest(master, msg)
      } else {
        updateMaster(msg.partition, init = false)
        publish(msg)
      }
    })
  }

  /**
    * Stop this agent
    */
  def stop() = {
    PeerAgent.logger.info(s"P2PAgent of ${producer.name} is shutting down.")
    isRunning.set(false)
    zkConnectionValidator.join()
    //to avoid infinite polling block
    masterRequestsExecutor.shutdownOrDie(100, TimeUnit.SECONDS)
    newTxnExecutors.foreach(x => { x._2.shutdownOrDie(100, TimeUnit.SECONDS) })
    materializationExecutors.foreach(x => { x._2.shutdownOrDie(100, TimeUnit.SECONDS) })
    cassandraAsyncExecutor.shutdownOrDie(100, TimeUnit.SECONDS)
    publishExecutors.foreach(x => { x._2.shutdownOrDie(100, TimeUnit.SECONDS)})
    transport.stop()
  }

  def handleMessage(channel: Channel, rawMessage: String): Unit = {
    val agent = this
    try {
      val request: IMessage = ProtocolMessageSerializer.deserialize(rawMessage)
      request.channel = channel
      if (PeerAgent.logger.isDebugEnabled)
        PeerAgent.logger.debug(s"[HANDLER] Start handle msg:{$request} on agent:{$myIPAddress}")
      val task: Runnable = new Runnable {
        override def run(): Unit = request.run(agent)
      }

      assert(partitionsToExecutors.contains(request.partition))
      val execNo = partitionsToExecutors(request.partition)
      request match {
        case _: PublishRequest => if(!publishExecutors(execNo).isShutdown) publishExecutors(execNo).submit(task)
        case _: NewTransactionRequest => if(!newTxnExecutors(execNo).isShutdown) newTxnExecutors(execNo).submit(task)
        case _: MaterializeRequest => if(!materializationExecutors(execNo).isShutdown) materializationExecutors(execNo).submit(task)
        case _ => if(!masterRequestsExecutor.isShutdown) masterRequestsExecutor.submit(task)
      }

    } catch {
      case e: ProtocolMessageSerializerException =>
        PeerAgent.logger.warn(s"Message '${rawMessage}' cannot be deserialized. Exception is: ${e.getMessage}")
    }
  }

  /**
    * public method which allows to submit delayed task for execution
    *
    * @param task
    * @param partition
    */
  def submitPipelinedTaskToPublishExecutors(task: Runnable, partition: Int) = {
    val execNum = partitionsToExecutors(partition)
    publishExecutors(execNum).execute(task)
  }

  /**
    * public method which allows to submit delayed task for execution
    *
    * @param task
    */
  def submitPipelinedTaskToCassandraExecutor(task: Runnable) = {
    cassandraAsyncExecutor.execute(task)
  }

  /**
    * public method which allows to submit delayed task for execution
    *
    * @param task
    * @param partition
    */
  def submitPipelinedTaskToNewTxnExecutors(task: Runnable, partition: Int) = {
    val execNum = partitionsToExecutors(partition)
    newTxnExecutors(execNum).execute(task)
  }
}