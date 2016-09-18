package agents.subscriber


import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import com.bwsw.tstreams.agents.consumer.subscriber.{QueueBuilder, TransactionBuffer, TransactionState}
import com.bwsw.tstreams.coordination.messages.state.TransactionStatus
import org.scalatest.{FlatSpec, Matchers}

object TransactionBufferTests {
  val OPENED = 0
  val UPDATE = 1
  val PRE = 2
  val POST = 3
  val CANCEL = 4
  val UPDATE_TTL = 20
  val OPEN_TTL = 10
  val cntr = new AtomicLong(0)

  def generateAllStates(): Array[TransactionState] = {
    val id = cntr.incrementAndGet()
    Array[TransactionState](
      TransactionState(id, 0, 0, 0, -1, TransactionStatus.opened, OPEN_TTL),
      TransactionState(id, 0, 0, 0, -1, TransactionStatus.update, UPDATE_TTL),
      TransactionState(id, 0, 0, 0, -1, TransactionStatus.preCheckpoint, 10),
      TransactionState(id, 0, 0, 0, -1, TransactionStatus.postCheckpoint, 10),
      TransactionState(id, 0, 0, 0, -1, TransactionStatus.cancel, 10))
  }
}

/**
  * Created by Ivan Kudryavtsev on 19.08.16.
  */
class TransactionBufferTests extends FlatSpec with Matchers {

  val OPENED = TransactionBufferTests.OPENED
  val UPDATE = TransactionBufferTests.UPDATE
  val PRE = TransactionBufferTests.PRE
  val POST = TransactionBufferTests.POST
  val CANCEL = TransactionBufferTests.CANCEL
  val UPDATE_TTL = TransactionBufferTests.UPDATE_TTL
  val OPEN_TTL = TransactionBufferTests.OPEN_TTL

  def generateAllStates() = TransactionBufferTests.generateAllStates()


  it should "be created" in {
    val b = new TransactionBuffer(new QueueBuilder.InMemory().generateQueueObject(0))
  }

  it should "avoid addition of update state if no previous state" in {
    val b = new TransactionBuffer(new QueueBuilder.InMemory().generateQueueObject(0))
    val ts0 = generateAllStates()
    b.update(ts0(UPDATE))
    b.getState(ts0(UPDATE).transactionID).isDefined shouldBe false
  }

  it should "avoid addition of pre state if no previous state" in {
    val b = new TransactionBuffer(new QueueBuilder.InMemory().generateQueueObject(0))
    val ts0 = generateAllStates()
    b.update(ts0(PRE))
    b.getState(ts0(PRE).transactionID).isDefined shouldBe false
  }

  it should "avoid addition of post state if no previous state" in {
    val b = new TransactionBuffer(new QueueBuilder.InMemory().generateQueueObject(0))
    val ts0 = generateAllStates()
    b.update(ts0(POST))
    b.getState(ts0(POST).transactionID).isDefined shouldBe false
  }

  it should "avoid addition of cancel state if no previous state" in {
    val b = new TransactionBuffer(new QueueBuilder.InMemory().generateQueueObject(0))
    val ts0 = generateAllStates()
    b.update(ts0(CANCEL))
    b.getState(ts0(CANCEL).transactionID).isDefined shouldBe false
  }

  it should "avoid addition of ts0 after ts1" in {
    val b = new TransactionBuffer(new QueueBuilder.InMemory().generateQueueObject(0))
    val ts0 = generateAllStates()
    val ts1 = generateAllStates()
    b.update(ts1(OPENED))
    b.update(ts0(OPENED))
    b.getState(ts0(OPENED).transactionID).isDefined shouldBe false
    b.getState(ts1(OPENED).transactionID).isDefined shouldBe true
  }


  it should "allow to place opened state" in {
    val b = new TransactionBuffer(new QueueBuilder.InMemory().generateQueueObject(0))
    val ts0 = generateAllStates()
    b.update(ts0(OPENED))
    b.getState(ts0(OPENED).transactionID).isDefined shouldBe true
  }

  it should "move from opened to updated without problems, state should be opened, ttl must be changed" in {
    val b = new TransactionBuffer(new QueueBuilder.InMemory().generateQueueObject(0))
    val ts0 = generateAllStates()
    b.update(ts0(OPENED))
    b.update(ts0(UPDATE))
    b.getState(ts0(UPDATE).transactionID).isDefined shouldBe true
    Math.abs(b.getState(ts0(UPDATE).transactionID).get.ttl - UPDATE_TTL * 1000 - System.currentTimeMillis()) < 20 shouldBe true
    b.getState(ts0(UPDATE).transactionID).get.state shouldBe TransactionStatus.opened
  }

  it should "move from opened to cancelled" in {
    val b = new TransactionBuffer(new QueueBuilder.InMemory().generateQueueObject(0))
    val ts0 = generateAllStates()
    b.update(ts0(OPENED))
    b.update(ts0(CANCEL))
    b.getState(ts0(UPDATE).transactionID).isDefined shouldBe false
  }

  it should "move from opened to preCheckpoint to Cancel" in {
    val b = new TransactionBuffer(new QueueBuilder.InMemory().generateQueueObject(0))
    val ts0 = generateAllStates()
    b.update(ts0(OPENED))
    val shouldBeTime = TransactionBuffer.MAX_POST_CHECKPOINT_WAIT + System.currentTimeMillis()
    b.update(ts0(PRE))
    b.getState(ts0(PRE).transactionID).isDefined shouldBe true
    b.getState(ts0(PRE).transactionID).get.state shouldBe TransactionStatus.preCheckpoint
    Math.abs(b.getState(ts0(PRE).transactionID).get.ttl - shouldBeTime) < 20 shouldBe true
    b.update(ts0(CANCEL))
    b.getState(ts0(CANCEL).transactionID).isDefined shouldBe false
  }

  it should "move from opened to preCheckpoint to postCheckpoint" in {
    val b = new TransactionBuffer(new QueueBuilder.InMemory().generateQueueObject(0))
    val ts0 = generateAllStates()
    b.update(ts0(OPENED))
    val shouldBeTime = TransactionBuffer.MAX_POST_CHECKPOINT_WAIT + System.currentTimeMillis()
    b.update(ts0(PRE))
    b.getState(ts0(PRE).transactionID).isDefined shouldBe true
    b.getState(ts0(PRE).transactionID).get.state shouldBe TransactionStatus.preCheckpoint
    Math.abs(b.getState(ts0(PRE).transactionID).get.ttl - shouldBeTime) < 20 shouldBe true
    b.update(ts0(POST))
    b.getState(ts0(POST).transactionID).isDefined shouldBe true
    b.getState(ts0(POST).transactionID).get.ttl shouldBe Long.MaxValue
  }

  it should "move from opened to preCheckpoint update stay in preCheckpoint" in {
    val b = new TransactionBuffer(new QueueBuilder.InMemory().generateQueueObject(0))
    val ts0 = generateAllStates()
    b.update(ts0(OPENED))
    val shouldBeTime = TransactionBuffer.MAX_POST_CHECKPOINT_WAIT + System.currentTimeMillis()
    b.update(ts0(PRE))
    b.getState(ts0(PRE).transactionID).isDefined shouldBe true
    b.getState(ts0(PRE).transactionID).get.state shouldBe TransactionStatus.preCheckpoint
    Math.abs(b.getState(ts0(PRE).transactionID).get.ttl - shouldBeTime) < 20 shouldBe true
    b.update(ts0(UPDATE))
    b.getState(ts0(PRE).transactionID).isDefined shouldBe true
    b.getState(ts0(PRE).transactionID).get.state shouldBe TransactionStatus.preCheckpoint
    Math.abs(b.getState(ts0(PRE).transactionID).get.ttl - shouldBeTime) < 20 shouldBe true
  }

  it should "move to preCheckpoint impossible" in {
    val b = new TransactionBuffer(new QueueBuilder.InMemory().generateQueueObject(0))
    val ts0 = generateAllStates()
    b.update(ts0(PRE))
    b.getState(ts0(PRE).transactionID).isDefined shouldBe false
  }

  it should "move to postCheckpoint impossible" in {
    val b = new TransactionBuffer(new QueueBuilder.InMemory().generateQueueObject(0))
    val ts0 = generateAllStates()
    b.update(ts0(POST))
    b.getState(ts0(POST).transactionID).isDefined shouldBe false
  }

  it should "move to cancel impossible" in {
    val b = new TransactionBuffer(new QueueBuilder.InMemory().generateQueueObject(0))
    val ts0 = generateAllStates()
    b.update(ts0(CANCEL))
    b.getState(ts0(CANCEL).transactionID).isDefined shouldBe false
  }

  it should "move to update impossible" in {
    val b = new TransactionBuffer(new QueueBuilder.InMemory().generateQueueObject(0))
    val ts0 = generateAllStates()
    b.update(ts0(UPDATE))
    b.getState(ts0(UPDATE).transactionID).isDefined shouldBe false
  }

  it should "signal for one completed transaction" in {
    val q = new QueueBuilder.InMemory().generateQueueObject(0)
    val b = new TransactionBuffer(q)
    val ts0 = generateAllStates()
    val ts1 = generateAllStates()
    b.update(ts0(OPENED))
    b.update(ts0(PRE))
    b.update(ts0(POST))
    b.signalCompleteTransactions()
    val r = q.get(1, TimeUnit.MILLISECONDS)
    r.size shouldBe 1
    r.head.transactionID shouldBe ts0(OPENED).transactionID
    b.getState(ts0(OPENED).transactionID).isDefined shouldBe false
  }


  it should "signal for two completed transactions" in {
    val q = new QueueBuilder.InMemory().generateQueueObject(0)
    val b = new TransactionBuffer(q)
    val ts0 = generateAllStates()
    val ts1 = generateAllStates()
    b.update(ts0(OPENED))
    b.update(ts1(OPENED))
    b.update(ts0(PRE))
    b.update(ts1(PRE))
    b.update(ts0(POST))
    b.update(ts1(POST))
    b.signalCompleteTransactions()
    val r = q.get(1, TimeUnit.MILLISECONDS)
    r.size shouldBe 2
    r.head.transactionID shouldBe ts0(OPENED).transactionID
    r.tail.head.transactionID shouldBe ts1(OPENED).transactionID
  }

  it should "signal for first incomplete, second completed" in {
    val q = new QueueBuilder.InMemory().generateQueueObject(0)
    val b = new TransactionBuffer(q)
    val ts0 = generateAllStates()
    val ts1 = generateAllStates()
    b.update(ts0(OPENED))
    b.update(ts1(OPENED))
    b.update(ts0(PRE))
    b.update(ts1(PRE))
    b.update(ts1(POST))
    b.signalCompleteTransactions()
    val r = q.get(1, TimeUnit.MILLISECONDS)
    r shouldBe null
  }

  it should "signal for first incomplete, second incomplete" in {
    val q = new QueueBuilder.InMemory().generateQueueObject(0)
    val b = new TransactionBuffer(q)
    val ts0 = generateAllStates()
    val ts1 = generateAllStates()
    b.update(ts0(OPENED))
    b.update(ts1(OPENED))
    b.update(ts0(PRE))
    b.update(ts1(PRE))
    b.signalCompleteTransactions()
    val r = q.get(1, TimeUnit.MILLISECONDS)
    r shouldBe null
  }

  it should "signal for first complete, second incomplete" in {
    val q = new QueueBuilder.InMemory().generateQueueObject(0)
    val b = new TransactionBuffer(q)
    val ts0 = generateAllStates()
    val ts1 = generateAllStates()
    b.update(ts0(OPENED))
    b.update(ts1(OPENED))
    b.update(ts0(PRE))
    b.update(ts0(POST))
    b.signalCompleteTransactions()
    val r = q.get(1, TimeUnit.MILLISECONDS)
    r.size shouldBe 1
  }

  it should "signal for first incomplete, second complete, first complete" in {
    val q = new QueueBuilder.InMemory().generateQueueObject(0)
    val b = new TransactionBuffer(q)
    val ts0 = generateAllStates()
    val ts1 = generateAllStates()
    b.update(ts0(OPENED))
    b.update(ts1(OPENED))
    b.update(ts1(PRE))
    b.update(ts0(PRE))
    b.update(ts1(POST))
    b.update(ts0(POST))
    b.signalCompleteTransactions()
    val r = q.get(1, TimeUnit.MILLISECONDS)
    r.size shouldBe 2
    r.head.transactionID shouldBe ts0(OPENED).transactionID
    r.tail.head.transactionID shouldBe ts1(OPENED).transactionID
  }
}