/*
 * Copyright (c) 2014-2019 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics
package snowplow
package enrich
package common
package enrichments
package registry

// Java
import java.net.URI

// Apache
import org.apache.http.client.utils.URIBuilder

// Scala
import scala.util.Try
import cats.data.EitherT
import cats.effect.IO
import scalaz._

// json4s
import org.json4s.{DefaultFormats, JValue}

// Iglu
import com.snowplowanalytics.iglu.client.{SchemaCriterion, SchemaKey}

// Snowplow referer-parser
import com.snowplowanalytics.refererparser.{Parser, Referer, SearchReferer}

// This project
import com.snowplowanalytics.snowplow.enrich.common.utils.{ScalazJson4sUtils, ConversionUtils => CU}

/**
 * Companion object. Lets us create a
 * RefererParserEnrichment from a JValue
 */
object RefererParserEnrichment extends ParseableEnrichment {

  implicit val formats = DefaultFormats

  val supportedSchema = SchemaCriterion("com.globeandmail", "sophi_referer_parser", "jsonschema", 1, 0)

  /**
   * Creates a RefererParserEnrichment instance from a JValue.
   *
   * @param config The referer_parser enrichment JSON
   * @param schemaKey The SchemaKey provided for the enrichment
   *        Must be a supported SchemaKey for this enrichment
   * @return a configured RefererParserEnrichment instance
   */
  def parse(config: JValue, schemaKey: SchemaKey): ValidatedNelMessage[RefererParserEnrichment] =
    isParseable(config, schemaKey).flatMap(conf => {
      (for {
        param <- ScalazJson4sUtils.extract[List[String]](config, "parameters", "internalDomains")
        referers = ScalazJson4sUtils.extract[String](config, "parameters", "referersLocation").toOption
        enrich   = RefererParserEnrichment(param, referers)
      } yield enrich).toValidationNel
    })

}

/**
 * Config for a referer_parser enrichment
 *
 * @param domains List of internal domains
 * @param referersPath Location of referers JSON
 */
case class RefererParserEnrichment(
  domains: List[String],
  referersPath: Option[String]
) extends Enrichment {

  private val referersJsonPath = referersPath.getOrElse("/referers.json")

  /**
   * A Scalaz Lens to update the term within
   * a Referer object.
   */
  private val termLens: Lens[SearchReferer, MaybeString] = Lens.lensu((r, newTerm) => r.copy(term = newTerm), _.term)

  /**
   * Extract details about the referer (sic).
   *
   * Uses the referer-parser library.
   *
   * @param uri The referer URI to extract
   *            referer details from
   * @param pageHost The host of the current
   *                 page (used to determine
   *                 if this is an internal
   *                 referer)
   * @return a Tuple3 containing referer medium,
   *         source and term, all Strings
   */
  def extractRefererDetails(uri: URI, pageHost: String): EitherT[IO, Exception, Option[Referer]] = {
    val validSchemes = Seq("android-app")
    val fixedURI =
      if (validSchemes.contains(uri.getScheme)) new URIBuilder(uri.toString).setScheme("http").build else uri
    val io: EitherT[IO, Exception, Option[Referer]] = for {
      parser <- EitherT(Parser.create[IO](getClass.getResource(referersJsonPath).getPath))
      r <- EitherT
        .fromOption[IO](parser.parse(fixedURI, Some(pageHost), domains),
                        new Exception("No parsable referer found in the URI"))
      t = r match {
        case s: SearchReferer => s.term.flatMap(t => CU.fixTabsNewlines(t))
        case _                => None
      }
    } yield {
      (r, t) match {
        case (r: SearchReferer, s) => if (s.isDefined) Some(r.copy(term = s)) else Some(r)
        case _                     => Some(r)
      }
    }
    io
  }
}
