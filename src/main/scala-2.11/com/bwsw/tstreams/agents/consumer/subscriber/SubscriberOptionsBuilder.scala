package com.bwsw.tstreams.agents.consumer.subscriber

import java.net.InetSocketAddress

import com.bwsw.tstreams.agents.consumer
import com.bwsw.tstreams.agents.consumer.subscriber.QueueBuilder.InMemory

object SubscriberOptionsBuilder {
  def fromConsumerOptions[T](consumerOpts: consumer.ConsumerOptions[T],
                             agentAddress: String,
                             zkRootPath: String,
                             zkHosts: Set[InetSocketAddress],
                             zkSessionTimeout: Int,
                             zkConnectionTimeout: Int,
                             transactionsBufferWorkersThreadPoolAmount: Int = 1,
                             processingEngineWorkersThreadAmount: Int = 1,
                             pollingFrequencyDelay: Int = 1000,
                             transactionsQueueBuilder: QueueBuilder.Abstract = new InMemory): SubscriberOptions[T] =
    new SubscriberOptions[T](
      transactionsPreload = consumerOpts.transactionsPreload,
      dataPreload = consumerOpts.dataPreload,
      converter = consumerOpts.converter,
      readPolicy = consumerOpts.readPolicy,
      offset = consumerOpts.offset,
      transactionGenerator = consumerOpts.transactionGenerator,
      useLastOffset = consumerOpts.useLastOffset,
      agentAddress = agentAddress,
      zkRootPath = zkRootPath,
      zkHosts = zkHosts,
      zkSessionTimeout = zkSessionTimeout,
      zkConnectionTimeout = zkConnectionTimeout,
      transactionBufferWorkersThreadPoolAmount = transactionsBufferWorkersThreadPoolAmount,
      processingEngineWorkersThreadAmount = processingEngineWorkersThreadAmount,
      pollingFrequencyDelay = pollingFrequencyDelay,
      transactionsQueueBuilder = transactionsQueueBuilder)
}
