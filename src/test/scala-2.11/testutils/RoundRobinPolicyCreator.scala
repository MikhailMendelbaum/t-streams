package testutils

import com.bwsw.tstreams.common.RoundRobinPolicy
import com.bwsw.tstreams.streams.TStream

/**
  * Repo for creating some defined policies
  */
object RoundRobinPolicyCreator {
  /**
    *
    * @param stream         Stream instance
    * @param usedPartitions Policy partitions to use
    * @return RoundRobinPolicy instance
    */
  def getRoundRobinPolicy(stream: TStream[_], usedPartitions: List[Int]): RoundRobinPolicy =
    new RoundRobinPolicy(stream, usedPartitions)
}