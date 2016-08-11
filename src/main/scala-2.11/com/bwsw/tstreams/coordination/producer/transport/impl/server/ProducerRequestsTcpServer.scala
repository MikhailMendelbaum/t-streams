package com.bwsw.tstreams.coordination.producer.transport.impl.server

import java.util.concurrent.{CountDownLatch, TimeUnit}

import com.bwsw.tstreams.coordination.messages.master.IMessage
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.string.{StringDecoder, StringEncoder}
import io.netty.handler.codec.{DelimiterBasedFrameDecoder, Delimiters}
import io.netty.handler.logging.{LogLevel, LoggingHandler}

/**
  * @param port Listener port
  */
class ProducerRequestsTcpServer(port: Int) {
  //socket accept worker
  private val bossGroup = new NioEventLoopGroup(1)
  //channel workers
  private val workerGroup = new NioEventLoopGroup()
  private val MAX_FRAME_LENGTH = 8192
  private val manager = new ProducerRequestsMessageManager()
  private val channelHandler: ProducerRequestsChannelHandler = new ProducerRequestsChannelHandler(manager)
  private var listenerThread: Thread = null

  /**
    * Stop this listener
    */
  def stop(): Unit = {
    workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS).await()
    bossGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS).await()
  }

  /**
    * Add callback on incoming [[IMessage]]] events
    *
    * @param callback Event callback
    */
  def addCallback(callback: (IMessage) => Unit): Unit = {
    manager.addCallback(callback)
  }

  /**
    * Response with [[IMessage]]]
    */
  def respond(msg: IMessage): Unit = {
    manager.respond(msg)
  }

  /**
    * Start this listener
    */
  def start() = {
    assert(listenerThread == null || !listenerThread.isAlive)
    val syncPoint = new CountDownLatch(1)
    listenerThread = new Thread(new Runnable {
      override def run(): Unit = {
        try {
          val b = new ServerBootstrap()
          b.group(bossGroup, workerGroup).channel(classOf[NioServerSocketChannel])
            .handler(new LoggingHandler(LogLevel.DEBUG))
            .childHandler(new ChannelInitializer[SocketChannel]() {
              override def initChannel(ch: SocketChannel) {
                ch.config().setTcpNoDelay(true)
                val p = ch.pipeline()
                p.addLast("framer", new DelimiterBasedFrameDecoder(MAX_FRAME_LENGTH, Delimiters.lineDelimiter(): _*))
                p.addLast("decoder", new StringDecoder())
                p.addLast("deserializer", new ProducerRequestsMessageDecoder())
                p.addLast("encoder", new StringEncoder())
                p.addLast("serializer", new ProducerRequestsMessageEncoder())
                p.addLast("handler", channelHandler)
              }
            })
          val f = b.bind(port).sync()
          syncPoint.countDown()
          f.channel().closeFuture().sync()
        } finally {
          workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS)
          bossGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS)
        }
      }
    })
    listenerThread.start()
    syncPoint.await()
  }
}