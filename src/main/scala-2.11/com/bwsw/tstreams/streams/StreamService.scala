package com.bwsw.tstreams.streams

import com.bwsw.tstreams.data.IStorage
import com.bwsw.tstreams.metadata.MetadataStorage
import org.slf4j.LoggerFactory


/**
  * Service for streams
  */
object StreamService {

  /**
    * Basic Stream logger for logging
    */
  private val logger = LoggerFactory.getLogger(this.getClass)

  /**
    * Getting existing stream
    *
    * @param streamName      Name of the stream
    * @param metadataStorage Metadata storage of concrete stream
    * @param dataStorage     Data storage of concrete stream
    * @return Stream instance
    * @tparam T Type of stream data
    */
  def loadStream[T](streamName: String,
                    metadataStorage: MetadataStorage,
                    dataStorage: IStorage[T]): Stream[T] = {
    val settingsOpt: Option[StreamSettings] = Stream.getStream(metadataStorage.getSession(), streamName)
    if (settingsOpt.isEmpty)
      throw new IllegalArgumentException("stream with this name can not be loaded")
    else {
      val settings = settingsOpt.get
      val (name: String, partitions: Int, ttl: Int, description: String) =
        (settings.name, settings.partitions, settings.ttl, settings.description)

      val stream: Stream[T] = new Stream(name, partitions, metadataStorage, dataStorage, ttl, description)
      stream
    }
  }

  /**
    * Creating stream
    *
    * @param streamName      Name of the stream
    * @param partitions      Number of stream partitions
    * @param metadataStorage Metadata storage using by this stream
    * @param dataStorage     Data storage using by this stream
    * @param description     Some additional info about stream
    * @param ttl             Expiration time of single transaction in seconds
    * @tparam T Type of stream data
    */
  def createStream[T](streamName: String,
                      partitions: Int,
                      ttl: Int,
                      description: String,
                      metadataStorage: MetadataStorage,
                      dataStorage: IStorage[T]): Stream[T] = {

    Stream.createStream(metadataStorage.getSession(), streamName, partitions, ttl, description)
    new Stream[T](streamName, partitions, metadataStorage, dataStorage, ttl, description)
  }


  /**
    * Deleting concrete stream
    *
    * @param streamName      Name of the stream to delete
    * @param metadataStorage Name of metadata storage where concrete stream exist
    */
  def deleteStream(streamName: String, metadataStorage: MetadataStorage): Unit = {
    Stream.deleteStream(metadataStorage.getSession(), streamName)
  }


  /**
    * Checking exist concrete stream or not
    *
    * @param streamName      Name of the stream to check
    * @param metadataStorage Name of metadata storage where concrete stream exist
    */
  def isExist(streamName: String, metadataStorage: MetadataStorage): Boolean =
    Stream.isExist(metadataStorage.getSession(), streamName)

}