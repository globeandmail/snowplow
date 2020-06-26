/*
 * Copyright (c) 2013-2019 Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache
 * License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich.stream
package sources

import java.net.{InetAddress, URI}
import java.util.{List, UUID}

import scala.util.control.Breaks._
import scala.collection.JavaConverters._
import scala.util.control.NonFatal
import cats.Id
import cats.syntax.either._
import com.amazonaws.auth.AWSCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder
import software.amazon.kinesis.common.{InitialPositionInStream, InitialPositionInStreamExtended}
import software.amazon.kinesis.exceptions.ThrottlingException
import software.amazon.kinesis.metrics.NullMetricsFactory
import software.amazon.kinesis.processor.{RecordProcessorCheckpointer, ShardRecordProcessorFactory}
import software.amazon.kinesis.retrieval.KinesisClientRecord
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.kinesis.common.ConfigsBuilder
import software.amazon.kinesis.common.KinesisClientUtil
import software.amazon.kinesis.coordinator.Scheduler
import software.amazon.kinesis.exceptions.InvalidStateException
import software.amazon.kinesis.exceptions.ShutdownException
import software.amazon.kinesis.lifecycle.events.InitializationInput
import software.amazon.kinesis.lifecycle.events.LeaseLostInput
import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput
import software.amazon.kinesis.lifecycle.events.ShardEndedInput
import software.amazon.kinesis.lifecycle.events.ShutdownRequestedInput
import software.amazon.kinesis.processor.ShardRecordProcessor
import com.snowplowanalytics.iglu.client.Client
import com.snowplowanalytics.snowplow.badrows.Processor
import com.snowplowanalytics.snowplow.enrich.common.adapters.AdapterRegistry
import com.snowplowanalytics.snowplow.enrich.common.enrichments.EnrichmentRegistry
import com.snowplowanalytics.snowplow.scalatracker.Tracker
import io.circe.Json
import model.{Kinesis, StreamsConfig}
import sinks._
import software.amazon.awssdk.regions.Region
import utils.{getAWSCredentialsProvider, getAwsCredentialsProvider}

/** KinesisSource companion object with factory method */
object KinesisSource {
  def createAndInitialize(
    config: StreamsConfig,
    client: Client[Id, Json],
    adapterRegistry: AdapterRegistry,
    enrichmentRegistry: EnrichmentRegistry[Id],
    tracker: Option[Tracker[Id]],
    processor: Processor
  ): Either[String, KinesisSource] =
    for {
      kinesisConfig <- config.sourceSink match {
        case c: Kinesis => c.asRight
        case _ => "Configured source/sink is not Kinesis".asLeft
      }
      emitPii = utils.emitPii(enrichmentRegistry)
      _ <- KinesisSink.validate(kinesisConfig, config.out.enriched)
      _ <- utils.validatePii(emitPii, config.out.pii)
      _ <- KinesisSink.validate(kinesisConfig, config.out.bad)
      sourceProvider <- getAwsCredentialsProvider(kinesisConfig.aws)
      sinkProvider <- getAWSCredentialsProvider(kinesisConfig.aws)
    } yield new KinesisSource(
      client,
      adapterRegistry,
      enrichmentRegistry,
      tracker,
      processor,
      config,
      kinesisConfig,
      sourceProvider,
      sinkProvider
    )
}

/** Source to read events from a Kinesis stream */
class KinesisSource private (
  client: Client[Id, Json],
  adapterRegistry: AdapterRegistry,
  enrichmentRegistry: EnrichmentRegistry[Id],
  tracker: Option[Tracker[Id]],
  processor: Processor,
  config: StreamsConfig,
  kinesisConfig: Kinesis,
  sourceProvider: AwsCredentialsProvider,
  sinkProvider: AWSCredentialsProvider
) extends Source(client, adapterRegistry, enrichmentRegistry, processor, config.out.partitionKey) {

  override val MaxRecordSize = Some(1000000)

  private val kClient = {
    val endpointConfiguration =
      new EndpointConfiguration(kinesisConfig.streamEndpoint, kinesisConfig.region)
    AmazonKinesisClientBuilder
      .standard()
      .withCredentials(sinkProvider)
      .withEndpointConfiguration(endpointConfiguration)
      .build()
  }

  override val threadLocalGoodSink: ThreadLocal[Sink] = new ThreadLocal[Sink] {
    override def initialValue: Sink =
      new KinesisSink(
        kClient,
        kinesisConfig.backoffPolicy,
        config.buffer,
        config.out.enriched,
        tracker
      )
  }
  override val threadLocalPiiSink: Option[ThreadLocal[Sink]] = {
    val emitPii = utils.emitPii(enrichmentRegistry)
    utils
      .validatePii(emitPii, config.out.pii)
      .toOption
      .flatMap { _ =>
        config.out.pii.map { piiStreamName =>
          new ThreadLocal[Sink] {
            override def initialValue: Sink =
              new KinesisSink(
                kClient,
                kinesisConfig.backoffPolicy,
                config.buffer,
                piiStreamName,
                tracker
              )
          }
        }
      }
  }

  override val threadLocalBadSink: ThreadLocal[Sink] = new ThreadLocal[Sink] {
    override def initialValue: Sink =
      new KinesisSink(kClient, kinesisConfig.backoffPolicy, config.buffer, config.out.bad, tracker)
  }

  /** Never-ending processing loop over source stream. */
  override def run(): Unit = {
    val workerId = InetAddress.getLocalHost().getCanonicalHostName() + ":" + UUID.randomUUID()
    log.info("Using workerId: " + workerId)

    val kinesisClient = KinesisClientUtil.createKinesisAsyncClient(
      KinesisAsyncClient
        .builder()
        .credentialsProvider(sourceProvider)
        .endpointOverride(new URI(kinesisConfig.streamEndpoint))
        .region(Region.of(kinesisConfig.region))
    )
    val dynamoClient = DynamoDbAsyncClient
      .builder()
      .credentialsProvider(sourceProvider)
      .region(Region.of(kinesisConfig.region))
      .build()
    val cloudWatchClient = CloudWatchAsyncClient
      .builder()
      .credentialsProvider(sourceProvider)
      .region(Region.of(kinesisConfig.region))
      .build()

    log.info(s"Running: ${config.appName}.")
    log.info(s"Processing raw input stream: ${config.in.raw}")

    val rawEventProcessorFactory = new RawEventProcessorFactory()

    val configsBuilder = new ConfigsBuilder(
      config.in.raw,
      config.appName,
      kinesisClient,
      dynamoClient,
      cloudWatchClient,
      workerId,
      rawEventProcessorFactory
    )

    val position = InitialPositionInStream.valueOf(kinesisConfig.initialPosition)
    val position2 = kinesisConfig.timestamp.right.toOption
      .filter(_ => position == InitialPositionInStream.AT_TIMESTAMP)
      .map(InitialPositionInStreamExtended.newInitialPositionAtTimestamp(_))
      .getOrElse(InitialPositionInStreamExtended.newInitialPosition(position))

    val metricFactory = kinesisConfig.disableCloudWatch match {
      case Some(true) => new NullMetricsFactory()
      case _ => null // KCL internally creates it.
    }

    // where to put maxrecords to fetch?
    val scheduler = new Scheduler(
      configsBuilder.checkpointConfig(),
      configsBuilder.coordinatorConfig(),
      configsBuilder
        .leaseManagementConfig()
        // to do: make these below configurations.
        .initialLeaseTableReadCapacity(10)
        .initialLeaseTableWriteCapacity(10)
        .initialPositionInStream(position2),
      configsBuilder.lifecycleConfig(),
      configsBuilder.metricsConfig().metricsFactory(metricFactory),
      configsBuilder.processorConfig().callProcessRecordsEvenForEmptyRecordList(true),
      configsBuilder.retrievalConfig()
    )

    scheduler.run()
  }

  // Factory needed by the Amazon Kinesis Consumer library to
  // create a processor.
  class RawEventProcessorFactory extends ShardRecordProcessorFactory {
    override def shardRecordProcessor: ShardRecordProcessor = new RawEventProcessor()
  }

  // Process events from a Kinesis stream.
  class RawEventProcessor extends ShardRecordProcessor {
    private var kinesisShardId: String = _

    // Backoff and retry settings.
    // make these configurations with default values.
    private val BACKOFF_TIME_IN_MILLIS = 3000L
    private val NUM_RETRIES = 10

    override def initialize(initializationInput: InitializationInput) = {
      log.info(s"Initializing record processor for shard: ${initializationInput.shardId()}")
      this.kinesisShardId = initializationInput.shardId()
    }

    override def processRecords(processRecordsInput: ProcessRecordsInput) = {

      if (!processRecordsInput.records().isEmpty) {
        log.info(s"Processing ${processRecordsInput.records().size()} records from $kinesisShardId")
      }
      val shouldCheckpoint = processRecordsWithRetries(processRecordsInput.records())

      if (shouldCheckpoint) {
        checkpoint(processRecordsInput.checkpointer())
      }
    }

    private def processRecordsWithRetries(records: List[KinesisClientRecord]): Boolean =
      try {
        enrichAndStoreEvents(records.asScala.map(_.data().array).toList)
      } catch {
        case NonFatal(e) =>
          // TODO: send an event when something goes wrong here
          log.error(s"Caught throwable while processing records $records", e)
          false
      }

    def leaseLost(leaseLostInput: LeaseLostInput) =
      // do nothing. the other processor will take care of it.
      log.info(s"Least lost  ${leaseLostInput}")

    def shardEnded(shardEndedInput: ShardEndedInput) =
      try {
        // Not sure if this is the best behavior. Probably shouldn't checkpoint any more and exit.
        log.info(s"Shard ended for shard: $kinesisShardId")
        shardEndedInput.checkpointer().checkpoint()
      } catch {
        case e: ShutdownException =>
          log.error(s"Caught ShutdownException for endedShard ", e)
        case e: InvalidStateException =>
          log.error(s"Caught InvalidStateException for endedShard ", e)
      }

    def shutdownRequested(shutdownRequestedInput: ShutdownRequestedInput) =
      try {
        log.info(s"Shutting down record processor for shard: $kinesisShardId")
        shutdownRequestedInput.checkpointer().checkpoint()
      } catch {
        case e: ShutdownException =>
          log.error(s"Caught ShutdownException for shutdownRequestedInput", e)
        case e: InvalidStateException =>
          log.error(s"Caught InvalidStateException for shutdownRequestedInput", e)
      }

    private def checkpoint(checkpointer: RecordProcessorCheckpointer) = {
      log.info(s"Checkpointing shard $kinesisShardId")
      breakable {
        for (i <- 0 to NUM_RETRIES - 1) {
          try {
            checkpointer.checkpoint()
            break
          } catch {
            case se: ShutdownException =>
              log.error("Caught shutdown exception, skipping checkpoint.", se)
              break
            case e: ThrottlingException =>
              if (i >= (NUM_RETRIES - 1)) {
                log.error(s"Checkpoint failed after ${i + 1} attempts.", e)
              } else {
                log.info(
                  s"Transient issue when checkpointing - attempt ${i + 1} of "
                    + NUM_RETRIES,
                  e
                )
              }
            case e: InvalidStateException =>
              log.error(
                "Cannot save checkpoint to the DynamoDB table used by " +
                  "the Amazon Kinesis Client Library.",
                e
              )
              break
          }
          Thread.sleep(BACKOFF_TIME_IN_MILLIS)
        }
      }
    }
  }
}
