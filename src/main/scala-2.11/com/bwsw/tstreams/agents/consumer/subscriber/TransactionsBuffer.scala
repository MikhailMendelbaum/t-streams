package com.bwsw.tstreams.agents.consumer.subscriber

import java.util.UUID

import com.bwsw.tstreams.coordination.pubsub.messages.ProducerTransactionStatus
import ProducerTransactionStatus._
import com.bwsw.tstreams.coordination.pubsub.messages.ProducerTransactionStatus

/**
 * Buffer for maintaining consumed transactions in memory
 */
class TransactionsBuffer {
  private val map : SortedExpiringMap[UUID, (ProducerTransactionStatus, Long)] =
    new SortedExpiringMap(new UUIDComparator, new SubscriberExpirationPolicy)

  /**
   * Update transaction buffer
    *
    * @param txnUuid Transaction uuid
   * @param status Transaction status
   * @param ttl Transaction ttl(time of expiration)
   */
  def update(txnUuid : UUID, status: ProducerTransactionStatus, ttl : Int) : Unit = {
    //ignore update events until txn doesn't exist in buffer
    if (!map.exist(txnUuid) && status == ProducerTransactionStatus.updated) {
      return
    }

    if (map.exist(txnUuid)){
      map.get(txnUuid)._1 match {
        case ProducerTransactionStatus.preCheckpoint =>
          if (status != ProducerTransactionStatus.finalCheckpoint &&
              status != ProducerTransactionStatus.cancelled) {
            return
          }

        case ProducerTransactionStatus.finalCheckpoint =>
          return

        case _ =>
      }
    }

    status match {
      case ProducerTransactionStatus.updated =>
        map.put(txnUuid, (status, ttl))

      case ProducerTransactionStatus.opened =>
        map.put(txnUuid, (status, ttl))

      case ProducerTransactionStatus.preCheckpoint =>
        //ignore ttl, preCheckpoint will be resolved by another thread
        map.put(txnUuid, (status, -1))

      case ProducerTransactionStatus.finalCheckpoint =>
        //just ignore ttl because transaction is closed
        map.put(txnUuid, (status, -1))

      case ProducerTransactionStatus.cancelled =>
        map.remove(txnUuid)
    }
  }

  /**
   * Iterator on transaction buffer entry set
    *
    * @return Iterator
   */
  def getIterator() = map.entrySetIterator()
}
