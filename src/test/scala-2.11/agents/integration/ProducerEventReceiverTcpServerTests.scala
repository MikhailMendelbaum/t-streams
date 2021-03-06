package agents.integration

import com.bwsw.tstreams.coordination.messages.state.TransactionStateMessage
import com.bwsw.tstreams.coordination.subscriber.ProducerEventReceiverTcpServer
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

/**
  * Created by Ivan Kudryavtsev on 06.08.16.
  */
class ProducerEventReceiverTcpServerTests  extends FlatSpec with Matchers with BeforeAndAfterAll {

  "Normal lifecycle flow" should "be finished without errors" in {
    val s = new ProducerEventReceiverTcpServer("0.0.0.0",8000)
    var cnt: Int = 0
    val mf = (m: TransactionStateMessage) => { cnt +=1 }
    s.addCallbackToChannelHandler(mf)
    s.getConnectionsAmount() shouldBe 0
    s.start()
    s.stop()

    var flag = false
    try {
      s.stop()
    } catch {
      case e: IllegalStateException =>
        flag = true
    }
    flag shouldBe true

    flag = false
    try {
      s.start()
    } catch {
      case e: IllegalStateException =>
        flag = true
    }
    flag shouldBe true
  }

}
