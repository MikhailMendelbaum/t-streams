package com.bwsw.tstreams.common

import java.io.IOException
import java.net.{InetAddress, ServerSocket}

/**
  * Created by Ivan Kudryavtsev on 05.09.16.
  */
object SpareServerSocketLookupUtility {

  private def checkIfAvailable(hostOrIp: String, port: Int): Boolean = {
    var ss: ServerSocket = null

    try {
      ss = new ServerSocket(port, 1, InetAddress.getByName(hostOrIp))
      ss.setReuseAddress(true)
      ss.close()
      return true
    } catch {
      case e: IOException =>
    } finally {
      if (ss != null) ss.close()
    }
    return false
  }

  def findSparePort(hostOrIp: String, fromPort: Int, toPort: Int): Option[Int] = synchronized {
    (fromPort to toPort).find(port => checkIfAvailable(hostOrIp, port))
  }
}
