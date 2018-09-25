package com.snowplowanalytics
package snowplow
package enrich
package common
package adapters
package registry

import java.io.File
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

object RemoteAdapters {

  var EnrichActorSystem: Option[ActorSystem] = None
  var Adapters                               = Map.empty[(String, String), RemoteAdapter]

  def createFromConfigFile(configFilename: String) =
    if (configFilename != null) {
      try {
        val configFile = new File(configFilename)
        if (configFile.exists())
          createFromConfig(ConfigFactory.parseFile(configFile))
        else {
          System.err.println(s"RemoteAdapters config file '$configFilename' was not found!")
          Map.empty[(String, String), RemoteAdapter]
        }
      } catch {
        case e: Exception =>
          System.err.println(s"RemoteAdapters initialization failed! $e:")
          e.printStackTrace()
          Map.empty[(String, String), RemoteAdapter]
      }
    } else
      Map.empty[(String, String), RemoteAdapter]

  def createFromConfigString(config: String) =
    createFromConfig(ConfigFactory.parseString(config))

  def createFromConfig(userConfig: Config) = {
    val config = ConfigFactory.load(userConfig)

    if (EnrichActorSystem == null || EnrichActorSystem.isEmpty)
      EnrichActorSystem = Some(ActorSystem("Enrich", config))

    config
      .getConfigList("remoteAdapters")
      .asScala
      .toList
      .map { adapterConf =>
        val durationInMillis = adapterConf.getDuration("timeout", TimeUnit.MILLISECONDS)
        val adapter =
          new RemoteAdapter(EnrichActorSystem.get,
                            adapterConf.getString("url"),
                            new FiniteDuration(durationInMillis, TimeUnit.MILLISECONDS))

        (adapterConf.getString("vendor"), adapterConf.getString("version")) -> adapter
      }
      .toMap
  }

}
