/*
 * Copyright (c) 2013-2020 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
import sbt._

object Dependencies {

  val resolutionRepos = Seq(
    // For Snowplow
    "Snowplow Analytics Maven releases repo" at "http://maven.snplow.com/releases/",
    "Snowplow Analytics Maven snapshot repo" at "http://maven.snplow.com/snapshots/",
    "snowplow" at "https://snowplow.bintray.com/snowplow-maven",
    // For uaParser utils
    "user-agent-parser repo"                 at "https://clojars.org/repo/",
    // For user-agent-utils
    "user-agent-utils repo"                  at "https://raw.github.com/HaraldWalker/user-agent-utils/mvn-repo/"
  )

  object V {
    // Java
    val awsSdkV1             = "1.11.566"
    val awsSdk               = "2.13.42"
    val kinesisClient        = "2.2.11"
    val kafka                = "2.2.1"
    val nsqClient            = "1.2.0"
    val jackson              = "2.9.9"
    val config               = "1.3.4"
    val slf4j                = "1.7.26"
    val gcpSdk               = "1.106.0"
    // Scala
    val scopt                = "3.7.1"
    val pureconfig           = "0.11.0"
    val snowplowRawEvent     = "0.1.0"
    val snowplowCommonEnrich = "1.1.0"
    val igluClient           = "0.5.0"
    val snowplowTracker      = "0.6.1"
    // Scala (test only)
    val specs2               = "4.5.1"
    val scalacheck           = "1.14.0"
    val jinJava              = "2.5.0"
  }

  object Libraries {
    // Java
    val kinesisSdkV1         = "com.amazonaws"                    %  "aws-java-sdk-kinesis"      % V.awsSdkV1
    val dynamodbSdkV1        = "com.amazonaws"                    %  "aws-java-sdk-dynamodb"     % V.awsSdkV1
    val s3SdkV1              = "com.amazonaws"                    %  "aws-java-sdk-s3"           % V.awsSdkV1
    val authSdk              = "software.amazon.awssdk"           %  "auth"                      % V.awsSdk
    val kinesisSdk           = "software.amazon.awssdk"           %  "kinesis"                   % V.awsSdk
    val dynamodbSdk          = "software.amazon.awssdk"           %  "dynamodb"                  % V.awsSdk
    val kinesisClient        = "software.amazon.kinesis"          %  "amazon-kinesis-client"     % V.kinesisClient
    val kafkaClients         = "org.apache.kafka"                 %  "kafka-clients"             % V.kafka
    val nsqClient            = "com.snowplowanalytics"            %  "nsq-java-client"           % V.nsqClient
    val jacksonCbor          = "com.fasterxml.jackson.dataformat" %  "jackson-dataformat-cbor"   % V.jackson
    val jacksonDatabind      = "com.fasterxml.jackson.core"       %  "jackson-databind"          % V.jackson
    val config               = "com.typesafe"                     %  "config"                    % V.config
    val slf4j                = "org.slf4j"                        %  "slf4j-simple"              % V.slf4j
    val log4jOverSlf4j       = "org.slf4j"                        %  "log4j-over-slf4j"          % V.slf4j
    val gsSdk                = "com.google.cloud"                 %  "google-cloud-storage"      % V.gcpSdk
    // Scala
    val scopt                = "com.github.scopt"                 %% "scopt"                             % V.scopt
    val pureconfig           = "com.github.pureconfig"            %% "pureconfig"                        % V.pureconfig
    val snowplowRawEvent     = "com.snowplowanalytics"            %  "snowplow-thrift-raw-event"         % V.snowplowRawEvent
    val snowplowCommonEnrich = "com.snowplowanalytics"            %% "snowplow-common-enrich"            % V.snowplowCommonEnrich
    val snowplowTracker      = "com.snowplowanalytics"            %% "snowplow-scala-tracker-emitter-id" % V.snowplowTracker
    // Scala (test only)
    val specs2               = "org.specs2"          %% "specs2-core" % V.specs2     % "test"
    val scalacheck           = "org.scalacheck"      %% "scalacheck"  % V.scalacheck % "test"
    val kafka                = "org.apache.kafka"    %% "kafka"       % V.kafka      % "test"
    // Java (test only)
    val jinJava              = "com.hubspot.jinjava" %  "jinjava"     % V.jinJava    % "test"
  }
}
