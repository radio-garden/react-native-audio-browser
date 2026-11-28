package com.audiobrowser.browser

import com.audiobrowser.http.Fetched
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

  private fun TestScope.cache() =
    PageCache<String>(
      maxSize = 2,
      scope = backgroundScope,
      now = { testScheduler.currentTime },
      onExpired = { pushed += it },
    )

  private fun TestScope.advance(ms: Long) {
    advanceTimeBy(ms)
    runCurrent()
  }

  private fun TestScope.pendingTimers() =
    backgroundScope.coroutineContext[Job]!!.children.count { it.isActive }

  private fun page(value: String, lifetimeMs: Long? = null) = Fetched(value, lifetimeMs)

  private suspend fun assertFails(block: suspend () -> Unit) {
    try {
      block()
      fail("expected Unreachable")
    } catch (_: Unreachable) {}
  }

  @Test
  fun aPageWithNoLifetimeIsServedFromTheCacheForever() = runTest {
    val cache = cache()
    cache.value("/a", useCache = true) { page("first") }
    advance(1_000_000_000)

    assertEquals("first", cache.value("/a", useCache = true) { page("second") })
    assertEquals(emptyList<String>(), pushed)
  }

  @Test
  fun anExpiredPageIsPushedThenRefetched() = runTest {
    val cache = cache()
    cache.value("/a", useCache = true) { page("first", 60_000) }

    advance(59_000)
    assertEquals("first", cache.value("/a", useCache = true) { page("second", 60_000) })

    advance(2_000)
    assertEquals(listOf("/a"), pushed)
    assertEquals("second", cache.value("/a", useCache = true) { page("second", 60_000) })
  }

  @Test
  fun aFailedRefetchKeepsTheStalePageAndIsRetriedALifetimeLater() = runTest {
    val cache = cache()
    cache.value("/a", useCache = true) { page("first", 60_000) }
    advance(61_000)

    val stale = cache.value("/a", useCache = true) { throw Unreachable() }
    advance(61_000)

    assertEquals("first", stale)
    assertEquals(listOf("/a", "/a"), pushed)
  }

  @Test
  fun aPageStopsBeingPushedAfterItsLastRetryFails() = runTest {
    val cache = cache()
    cache.value("/a", useCache = true) { page("first", 60_000) }
    advance(61_000)

    repeat(PageCache.MAX_RETRIES + 1) {
      assertFails { cache.value("/a", useCache = false) { throw Unreachable() } }
      advance(61_000)
    }

    assertEquals(1 + PageCache.MAX_RETRIES, pushed.size)
    assertEquals(0, pendingTimers())
    assertEquals("second", cache.value("/a", useCache = true) { page("second", 60_000) })
  }

  @Test
  fun aFailedRefreshThrowsAndTheNextVisitStillGetsTheStalePage() = runTest {
    val cache = cache()
    cache.value("/a", useCache = true) { page("first", 60_000) }
    advance(61_000)

    assertFails { cache.value("/a", useCache = false) { throw Unreachable() } }
    assertEquals("first", cache.value("/a", useCache = true) { throw Unreachable() })
  }

  @Test
  fun aFailedRefreshOfAFreshPageLeavesItsExpiry() = runTest {
    val cache = cache()
    cache.value("/a", useCache = true) { page("first", 60_000) }
    advance(50_000)

    repeat(PageCache.MAX_RETRIES + 1) {
      assertFails { cache.value("/a", useCache = false) { throw Unreachable() } }
    }
    advance(11_000)

    assertEquals(listOf("/a"), pushed)
  }

  @Test
  fun aFailedRefetchKeepsAnUpdateMadeMeanwhile() = runTest {
    val cache = cache()
    cache.value("/a", useCache = true) { page("first", 60_000) }
    advance(61_000)

    val stale =
      cache.value("/a", useCache = true) {
        cache.update { "restamped" }
        throw Unreachable()
      }

    assertEquals("restamped", stale)
    assertEquals("restamped", cache.value("/a", useCache = true) { throw Unreachable() })
  }

  @Test
  fun aFailedRefreshLeavesAPageStoredMeanwhileInPlace() = runTest {
    val cache = cache()
    cache.value("/a", useCache = true) { page("first", 60_000) }
    advance(61_000)

    assertFails {
      cache.value("/a", useCache = false) {
        cache.value("/a", useCache = false) { page("fresh", 60_000) }
        throw Unreachable()
      }
    }
    advance(59_000)

    assertEquals("fresh", cache.value("/a", useCache = true) { throw Unreachable() })
    assertEquals(listOf("/a"), pushed)
  }

  @Test
  fun aStalePageRemovedDuringAFailedRefetchIsNotReturned() = runTest {
    for (clearingAll in listOf(true, false)) {
      val cache = cache()
      cache.value("/a", useCache = true) { page("first", 60_000) }
      advance(61_000)

      assertFails {
        cache.value("/a", useCache = true) {
          if (clearingAll) cache.clear() else cache.remove("/a")
          throw Unreachable()
        }
      }
    }
  }

  @Test
  fun aRemovedOrClearedPageIsNeverPushed() = runTest {
    val cache = cache()
    cache.value("/a", useCache = true) { page("a", 60_000) }
    cache.value("/b", useCache = true) { page("b", 60_000) }

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
    cache.value("/a", useCache = true) { page("first", 60_000) }
    advance(30_000)
    cache.value("/a", useCache = false) { page("second", 60_000) }

    assertEquals(1, pendingTimers())
    advance(31_000)
    assertEquals(emptyList<String>(), pushed)
    advance(30_000)
    assertEquals(listOf("/a"), pushed)
  }

  @Test
  fun anEvictedPageIsNeverPushed() = runTest {
    val cache = cache()
    cache.value("/a", useCache = true) { page("a", 60_000) }
    cache.value("/b", useCache = true) { page("b") }
    cache.value("/c", useCache = true) { page("c") }

    assertEquals(0, pendingTimers())
    advance(61_000)
    assertEquals(emptyList<String>(), pushed)
  }

  @Test
  fun anExpiringPageKeepsItsPlaceInTheEvictionOrder() = runTest {
    val cache = cache()
    cache.value("/a", useCache = true) { page("a", 60_000) }
    cache.value("/b", useCache = true) { page("b") }
    advance(61_000)

    cache.value("/c", useCache = true) { page("c") }

    assertEquals("b", cache.value("/b", useCache = true) { page("refetched") })
  }

  @Test
  fun anUpdatedPageKeepsItsExpiry() = runTest {
    val cache = cache()
    cache.value("/a", useCache = true) { page("first", 60_000) }

    cache.update { if (it == "first") "restamped" else null }
    advance(61_000)

    assertEquals(listOf("/a"), pushed)
    assertEquals("restamped", cache.value("/a", useCache = true) { throw Unreachable() })
  }

  @Test
  fun aFetchThatOutlivesARemovalCachesNothing() = runTest {
    val cache = cache()
    cache.value("/a", useCache = true) {
      cache.remove("/a")
      page("old")
    }
    cache.value("/b", useCache = true) {
      cache.clear()
      page("old")
    }

    assertEquals("new", cache.value("/a", useCache = true) { page("new") })
    assertEquals("new", cache.value("/b", useCache = true) { page("new") })
  }
}
