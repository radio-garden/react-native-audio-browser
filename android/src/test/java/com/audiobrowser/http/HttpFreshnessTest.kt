package com.audiobrowser.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpFreshnessTest {
  private fun lifetime(vararg headers: Pair<String, String>) =
    HttpFreshness.lifetimeMs(mapOf(*headers))

  @Test
  fun aResponseLivesForItsMaxAge() {
    assertEquals(60_000L, lifetime("Cache-Control" to "public, max-age=60"))
  }

  @Test
  fun aResponseThatStatesNoLifetimeHasNone() {
    assertNull(lifetime())
    assertNull(lifetime("Cache-Control" to "public"))
  }

  @Test
  fun noCacheOrNoStoreIsAlreadyStale() {
    listOf("no-cache", "no-store", "max-age=600, no-cache", "No-Store").forEach {
      assertEquals(it, 0L, lifetime("Cache-Control" to it))
    }
  }

  @Test
  fun ageIsSubtractedFromMaxAge() {
    assertEquals(500_000L, lifetime("Cache-Control" to "max-age=600", "Age" to "100"))
    assertEquals(0L, lifetime("Cache-Control" to "max-age=60", "Age" to "100"))
  }

  @Test
  fun directivesAreReadLooselyAsServersSendThem() {
    assertEquals(120_000L, lifetime("cache-control" to " MAX-AGE = \"120\" "))
  }

  @Test
  fun onlyDigitsStateAMaxAge() {
    listOf("max-age=soon", "max-age=inf", "max-age=1e999", "max-age=-5", "max-age=").forEach {
      assertNull(it, lifetime("Cache-Control" to it))
    }
  }

  @Test
  fun aMaxAgeBeyond2To31IsCapped() {
    assertEquals(2_147_483_648_000L, lifetime("Cache-Control" to "max-age=" + "9".repeat(400)))
  }

  @Test
  fun theFirstValidOfDuplicateMaxAgesWins() {
    assertEquals(60_000L, lifetime("Cache-Control" to "max-age=soon, max-age=60, max-age=600"))
  }

  @Test
  fun aBareEqualsSignIsSkipped() {
    assertEquals(60_000L, lifetime("Cache-Control" to "max-age=60,="))
  }

  @Test
  fun noCacheNamingAFieldStillLetsTheResponseBeReused() {
    assertEquals(600_000L, lifetime("Cache-Control" to "max-age=600, no-cache=\"Set-Cookie\""))
  }
}
