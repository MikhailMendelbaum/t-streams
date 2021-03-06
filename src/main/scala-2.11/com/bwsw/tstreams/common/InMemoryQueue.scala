package com.bwsw.tstreams.common

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
  * Created by Ivan Kudryavtsev on 19.08.16.
  */
class InMemoryQueue[T] extends AbstractQueue[T] {
  /**
    * Queue blocking stuff
    */
  private val mutex = new ReentrantLock(true)
  private val cond = mutex.newCondition()
  val q = new scala.collection.mutable.Queue[T]()

  override def put(elt: T) = {
    LockUtil.withLockOrDieDo[Unit](mutex, (100, TimeUnit.SECONDS), None, () => {
      q enqueue elt
      cond.signal()
    })
  }

  override def get(delay: Long, units: TimeUnit): T =
  LockUtil.withLockOrDieDo[T](mutex, (100, TimeUnit.SECONDS), None, () => {
    if(q.isEmpty && !cond.await(delay, units))
      return null.asInstanceOf[T]
    q dequeue()
  })
}
