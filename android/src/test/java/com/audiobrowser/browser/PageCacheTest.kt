package com.audiobrowser.browser

import com.audiobrowser.http.Fetched
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PageCacheTest {
  private class Unreachable : Exception()

  private val pushed = mutableListOf<String>()

  /** Time the device spent in deep sleep, which the clock counts and coroutine delays do not. */
  private var sleptMs = 0L

  private fun TestScope.cache() =
    PageCache<String>(
      maxSize = 2,
      scope = backgroundScope,
      now = { testScheduler.currentTime + sleptMs },
      onExpired = { pushed += it },
    )

  private fun TestScope.advance(ms: Long) {
    advanceTimeBy(ms)
    runCurrent()
  }

  private fun TestScope.pendingTimers() =
    backgroundScope.coroutineContext[Job]!!.children.count { it.isActive }

  private fun page(value: String, lifetimeMs: Long? = null, ageMs: Long = 0) =
    Fetched(value, lifetimeMs, ageMs)

  private suspend inline fun <reified E : Throwable> assertThrows(block: suspend () -> Unit) {
    try {
      block()
    } catch (e: Throwable) {
      if (e is E) return
      throw e
    }
    fail("expected ${E::class.simpleName}")
  }

  @Test
  fun aPageWithNoLifetimeIsServedFromTheCacheForever() = runTest {
    val cache = cache()
    cache.value("/a") { page("first") }
    advance(1_000_000_000)

    assertEquals("first", cache.value("/a") { page("second") })
    assertEquals(emptyList<String>(), pushed)
  }

  @Test
  fun anExpiredPageIsPushedThenRefetched() = runTest {
    val cache = cache()
    cache.value("/a") { page("first", 60_000) }

    advance(59_000)
    assertEquals("first", cache.value("/a") { page("second", 60_000) })

    advance(2_000)
    assertEquals(listOf("/a"), pushed)
    assertEquals("second", cache.value("/a") { page("second", 60_000) })
  }

  @Test
  fun theAgeAPageArrivedWithShortensItsLifetime() = runTest {
    val cache = cache()
    cache.value("/a") { page("first", 60_000, ageMs = 40_000) }

    advance(21_000)

    assertEquals(listOf("/a"), pushed)
    assertEquals("second", cache.value("/a") { page("second") })
  }

  @Test
  fun aPageWhoseAgeUsedUpItsLifetimeIsExpiredWithNoPush() = runTest {
    val cache = cache()
    cache.value("/a") { page("first", 60_000, ageMs = 100_000) }

    assertEquals(0, pendingTimers())
    assertEquals("second", cache.value("/a") { page("second") })
  }

  @Test
  fun aPageThatExpiredInDeepSleepIsPushedWithinAMinuteOfWaking() = runTest {
    val cache = cache()
    cache.value("/a") { page("first", 600_000) }

    sleptMs = 600_000
    advance(60_000)

    assertEquals(listOf("/a"), pushed)
  }

  @Test
  fun aFailedRefetchKeepsTheStalePageAndIsRetriedALifetimeLater() = runTest {
    val cache = cache()
    cache.value("/a") { page("first", 60_000) }
    advance(61_000)

    val stale = cache.value("/a") { throw Unreachable() }
    advance(61_000)

    assertEquals("first", stale)
    assertEquals(listOf("/a", "/a"), pushed)
  }

  @Test
  fun aPageStopsBeingPushedAfterItsLastRetryFails() = runTest {
    val cache = cache()
    cache.value("/a") { page("first", 60_000) }
    advance(61_000)

    repeat(PageCache.MAX_RETRIES + 1) {
      assertEquals("first", cache.value("/a") { throw Unreachable() })
      advance(61_000)
    }

    assertEquals(1 + PageCache.MAX_RETRIES, pushed.size)
    assertEquals(0, pendingTimers())
    assertEquals("second", cache.value("/a") { page("second", 60_000) })
  }

  @Test
  fun aVisitWhoseRefetchFailsAfterAnotherStoredAPageGetsThatPage() = runTest {
    val cache = cache()
    cache.value("/a") { page("first", 60_000) }
    advance(61_000)

    val failed =
      cache.value("/a") {
        cache.value("/a") { page("fresh", 60_000) }
        throw Unreachable()
      }
    advance(59_000)

    assertEquals("fresh", failed)
    assertEquals("fresh", cache.value("/a") { throw Unreachable() })
    assertEquals(listOf("/a"), pushed)
  }

  @Test
  fun aFailedRefetchKeepsAnUpdateMadeMeanwhile() = runTest {
    val cache = cache()
    cache.value("/a") { page("first", 60_000) }
    advance(61_000)

    val stale =
      cache.value("/a") {
        cache.update { "restamped" }
        throw Unreachable()
      }

    assertEquals("restamped", stale)
    assertEquals("restamped", cache.value("/a") { throw Unreachable() })
  }

  @Test
  fun aStalePageRemovedDuringAFailedRefetchIsNotReturned() = runTest {
    for (clearingAll in listOf(true, false)) {
      val cache = cache()
      cache.value("/a") { page("first", 60_000) }
      advance(61_000)

      assertThrows<Unreachable> {
        cache.value("/a") {
          if (clearingAll) cache.clear() else cache.remove("/a")
          throw Unreachable()
        }
      }
    }
  }

  @Test
  fun aCancelledRefetchThrowsAndKeepsNoRetry() = runTest {
    val cache = cache()
    cache.value("/a") { page("first", 60_000) }
    advance(61_000)

    assertThrows<CancellationException> { cache.value("/a") { throw CancellationException() } }
    advance(61_000)

    assertEquals(listOf("/a"), pushed)
    assertEquals(0, pendingTimers())
  }

  @Test
  fun aRemovedOrClearedPageIsNeverPushed() = runTest {
    val cache = cache()
    cache.value("/a") { page("a", 60_000) }
    cache.value("/b") { page("b", 60_000) }

    cache.remove("/a")
    assertEquals(1, pendingTimers())
    cache.clear()
    assertEquals(0, pendingTimers())
    advance(61_000)

    assertEquals(emptyList<String>(), pushed)
  }

  @Test
  fun aReplacedPageIsPushedAtItsOwnExpiryOnly() = runTest {
    val cache = cache()
    cache.value("/a") { page("first", 60_000) }
    advance(61_000)
    cache.value("/a") { page("second", 60_000) }
    pushed.clear()

    assertEquals(1, pendingTimers())
    advance(59_000)
    assertEquals(emptyList<String>(), pushed)
    advance(2_000)
    assertEquals(listOf("/a"), pushed)
  }

  @Test
  fun anEvictedPageIsNeverPushed() = runTest {
    val cache = cache()
    cache.value("/a") { page("a", 60_000) }
    cache.value("/b") { page("b") }
    cache.value("/c") { page("c") }

    assertEquals(0, pendingTimers())
    advance(61_000)
    assertEquals(emptyList<String>(), pushed)
  }

  @Test
  fun anExpiringPageKeepsItsPlaceInTheEvictionOrder() = runTest {
    val cache = cache()
    cache.value("/a") { page("a", 60_000) }
    cache.value("/b") { page("b") }
    advance(61_000)

    cache.value("/c") { page("c") }

    assertEquals("b", cache.value("/b") { page("refetched") })
  }

  @Test
  fun anUpdatedPageKeepsItsExpiry() = runTest {
    val cache = cache()
    cache.value("/a") { page("first", 60_000) }

    cache.update { if (it == "first") "restamped" else null }
    advance(61_000)

    assertEquals(listOf("/a"), pushed)
    assertEquals("restamped", cache.value("/a") { throw Unreachable() })
  }

  @Test
  fun aFetchThatOutlivesARemovalCachesNothing() = runTest {
    val cache = cache()
    cache.value("/a") {
      cache.remove("/a")
      page("old")
    }
    cache.value("/b") {
      cache.clear()
      page("old")
    }

    assertEquals("new", cache.value("/a") { page("new") })
    assertEquals("new", cache.value("/b") { page("new") })
  }
}
