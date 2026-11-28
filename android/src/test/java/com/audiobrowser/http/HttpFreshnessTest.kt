package com.audiobrowser.http

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpFreshnessTest {
  private fun freshness(vararg headers: Pair<String, String>) = HttpFreshness.of(mapOf(*headers))

  private fun maxAge(seconds: Long, ageSeconds: Long = 0) =
    HttpFreshness.MaxAge(seconds * 1000, ageSeconds * 1000)

  @Test
  fun aResponseLivesForItsMaxAge() {
    assertEquals(maxAge(60), freshness("Cache-Control" to "public, max-age=60"))
  }

  @Test
  fun aResponseThatStatesNothingIsUnstated() {
    assertEquals(HttpFreshness.Unstated, freshness())
    assertEquals(HttpFreshness.Unstated, freshness("Cache-Control" to "public"))
  }

  @Test
  fun noCacheNoStoreOrAZeroMaxAgeIsRevalidated() {
    listOf("no-cache", "no-store", "max-age=600, no-cache", "No-Store", "max-age=0").forEach {
      assertEquals(it, HttpFreshness.Revalidate, freshness("Cache-Control" to it))
    }
  }

  @Test
  fun theAgeIsKeptBesideTheMaxAge() {
    assertEquals(maxAge(60, 100), freshness("Cache-Control" to "max-age=60", "aGe" to "100"))
  }

  @Test
  fun ofRepeatedAgeLinesTheFirstCounts() {
    assertEquals(maxAge(600, 10), freshness("Cache-Control" to "max-age=600", "Age" to "10, 20"))
  }

  @Test
  fun directivesAreReadLooselyAsServersSendThem() {
    assertEquals(maxAge(120), freshness("cache-control" to " MAX-AGE = \"120\" "))
  }

  @Test
  fun anInvalidMaxAgeStatesNothing() {
    listOf("max-age=soon", "max-age=inf", "max-age=1e999", "max-age=-5", "max-age=").forEach {
      assertEquals(it, HttpFreshness.Unstated, freshness("Cache-Control" to it))
    }
  }

  @Test
  fun aMaxAgeBeyond2To31IsCapped() {
    assertEquals(maxAge(2_147_483_648), freshness("Cache-Control" to "max-age=" + "9".repeat(400)))
  }

  @Test
  fun theFirstValidOfDuplicateMaxAgesWins() {
    listOf("max-age=soon, max-age=60", "max-age=60, max-age=soon", "max-age=60, max-age=600")
      .forEach { assertEquals(it, maxAge(60), freshness("Cache-Control" to it)) }
  }

  @Test
  fun aBareEqualsSignIsSkipped() {
    assertEquals(maxAge(60), freshness("Cache-Control" to "max-age=60,="))
  }

  @Test
  fun noCacheNamingAFieldStillLetsTheResponseBeReused() {
    assertEquals(maxAge(600), freshness("Cache-Control" to "max-age=600, no-cache=\"Set-Cookie\""))
  }
}
