/*
 * Copyright (c) 2012-2019 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common
package enrichments
package registry

// Java
import java.net.URI

import cats.effect.IO
import com.snowplowanalytics.refererparser.{EmailReferer, InternalReferer, SearchReferer, SocialReferer, UnknownReferer}

// Specs2 & Scalaz-Specs2
import org.specs2.Specification
import org.specs2.matcher.DataTables
import org.specs2.scalaz.ValidationMatchers

// Scalaz
import scalaz._
import Scalaz._

// referer-parser
import com.snowplowanalytics.refererparser.{Medium, Referer}

/**
 * A small selection of tests partially borrowed from referer-parser.
 *
 * This is a very imcomplete set - more a tripwire than an exhaustive test.
 * Please see referer-parser's test suite for the full set of tests:
 *
 * https://github.com/snowplow/referer-parser/tree/master/java-scala/src/test/scala/com/snowplowanalytics/refererparser/scala
 */
class ExtractRefererDetailsSpec extends Specification with DataTables {
  def is = s2"""
  This is a specification to test extractRefererDetails
  Parsing referer URIs should work                                    $e1
  Tabs and newlines in search terms should be replaced                $e2
  Odd URI schemes should be handled                                   $e3
  Google quick search app should be handled with custom referers.json $e4
  """

  val PageHost = "www.snowplowanalytics.com"

  def e1 =
    "SPEC NAME"          || "REFERER URI" | "REFERER MEDIUM" | "REFERER SOURCE" | "REFERER TERM" |
      "Google search"    !! "http://www.google.com/search?q=gateway+oracle+cards+denise+linn&hl=en&client=safari" ! Medium.Search ! Some(
        "Google")        ! Some("gateway oracle cards denise linn") |
      "Facebook social"  !! "http://www.facebook.com/l.php?u=http%3A%2F%2Fwww.psychicbazaar.com&h=yAQHZtXxS&s=1" ! Medium.Social ! Some(
        "Facebook")      ! None |
      "Yahoo! Mail"      !! "http://36ohk6dgmcd1n-c.c.yom.mail.yahoo.net/om/api/1.0/openmail.app.invoke/36ohk6dgmcd1n/11/1.0.35/us/en-US/view.html/0" ! Medium.Email ! Some(
        "Yahoo! Mail")   ! None |
      "Internal referer" !! "https://www.snowplowanalytics.com/account/profile" ! Medium.Internal ! None ! None |
      "Custom referer"   !! "https://www.internaldomain.com/path" ! Medium.Internal ! None ! None |
      "Unknown referer"  !! "http://www.spyfu.com/domain.aspx?d=3897225171967988459" ! Medium.Unknown ! None ! None |> {
      (_, refererUri, _, _, _) =>
        val value: IO[Option[Referer]] = RefererParserEnrichment(List("www.internaldomain.com"), None)
          .extractRefererDetails(new URI(refererUri), PageHost)
          .getOrElse(None)
        val p = value.unsafeRunSync()
        p must beOneOf(
          Some(InternalReferer),
          Some(UnknownReferer),
          Some(SocialReferer("Facebook")),
          Some(EmailReferer("Yahoo! Mail")),
          Some(SearchReferer("Google", Some("gateway oracle cards denise linn")))
        )
    }

  def e2 = {
    val value: IO[Option[Referer]] = RefererParserEnrichment(List(), None)
      .extractRefererDetails(
        new URI("http://www.google.com/search?q=%0Agateway%09oracle%09cards%09denise%09linn&hl=en&client=safari"),
        PageHost)
      .getOrElse(None)
    value.unsafeRunSync() must beSome(SearchReferer("Google", Some("gateway    oracle    cards    denise    linn")))
  }

  def e3 = {
    val uri = new URI("android-app://m.facebook.com")
    val value: IO[Option[Referer]] = RefererParserEnrichment(List(), None)
      .extractRefererDetails(uri, PageHost)
      .getOrElse(None)
    value.unsafeRunSync() must beSome(SocialReferer("Facebook"))
  }

  def e4 = {
    val uri = new URI("android-app://com.google.android.googlequicksearchbox")
    val value: IO[Option[Referer]] = RefererParserEnrichment(List(), None)
      .extractRefererDetails(uri, PageHost)
      .getOrElse(None)
    value.unsafeRunSync() must beSome(SearchReferer("Google", None))
  }
}
