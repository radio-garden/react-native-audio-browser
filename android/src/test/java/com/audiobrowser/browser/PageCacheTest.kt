package com.audiobrowser.browser

import com.audiobrowser.http.Fetched
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
  /** A failure a refetch may survive, as a network error or a 5xx is. */
  private class Unreachable : Exception()

  /** A failure that ends a page, as a 404 or an undecodable body does. */
  private class NotFound : Exception()

  private val pushed = mutableListOf<String>()
  private val refreshed = mutableListOf<String>()
  private var online = true

  /** Time the device spent in deep sleep, which the clock counts and coroutine delays do not. */
  private var sleptMs = 0L

  private fun TestScope.cache(scope: CoroutineScope = backgroundScope) =
    PageCache<String>(
      maxSize = 2,
      scope = scope,
      now = { testScheduler.currentTime + sleptMs },
      canRefetch = { online },
      keepsStale = { it is Unreachable },
      onExpired = { pushed += it },
      onRefreshed = { refreshed += it },
    )

  private fun TestScope.advance(ms: Long) {
    advanceTimeBy(ms)
    runCurrent()
  }

  private fun TestScope.pendingTimers() =
    backgroundScope.coroutineContext[Job]!!.children.count { it.isActive }

  private fun page(value: String, lifetimeMs: Long? = null, ageMs: Long = 0) =
    Fetched(value, lifetimeMs, ageMs)

  /** [path] cached with a 60 s lifetime that has passed. */
  private suspend fun TestScope.expiredPage(cache: PageCache<String>, path: String = "/a") {
    cache.value(path) { page("first", 60_000) }
    advance(61_000)
  }

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
  fun anExpiredPageIsPushedThenServedAtOnceAndRefetchedBehindIt() = runTest {
    val cache = cache()
    cache.value("/a") { page("first", 60_000) }
    advance(59_000)
    assertEquals(emptyList<String>(), pushed)
    advance(2_000)
    assertEquals(listOf("/a"), pushed)

    val gate = CompletableDeferred<Unit>()
    assertEquals(
      "first",
      cache.value("/a") {
        gate.await()
        page("second", 60_000)
      },
    )
    runCurrent()
    assertEquals(emptyList<String>(), refreshed)

    gate.complete(Unit)
    runCurrent()
    assertEquals(listOf("/a"), refreshed)
    assertEquals("second", cache.value("/a") { page("third") })
  }

  @Test
  fun everyVisitDuringARefetchSharesIt() = runTest {
    val cache = cache()
    expiredPage(cache)
    val gate = CompletableDeferred<Unit>()
    var fetches = 0

    repeat(3) {
      cache.value("/a") {
        fetches += 1
        gate.await()
        page("second", 60_000)
      }
    }
    gate.complete(Unit)
    runCurrent()

    assertEquals(1, fetches)
    assertEquals(listOf("/a"), refreshed)
  }

  @Test
  fun firstVisitsMadeTogetherShareOneFetch() = runTest {
    val cache = cache()
    val gate = CompletableDeferred<Unit>()
    var fetches = 0

    val first = async {
      cache.value("/a") {
        fetches += 1
        gate.await()
        page("a")
      }
    }
    runCurrent()
    val second = async {
      cache.value("/a") {
        fetches += 1
        page("b")
      }
    }
    runCurrent()
    gate.complete(Unit)

    assertEquals("a", first.await())
    assertEquals("a", second.await())
    assertEquals(1, fetches)
  }

  @Test
  fun refetchesRunOneAtATime() = runTest {
    val cache = cache()
    cache.value("/a") { page("a", 60_000) }
    cache.value("/b") { page("b", 60_000) }
    advance(61_000)
    val gate = CompletableDeferred<Unit>()
    val started = mutableListOf<String>()

    cache.value("/a") {
      started += "/a"
      gate.await()
      page("a2")
    }
    cache.value("/b") {
      started += "/b"
      page("b2")
    }
    runCurrent()
    assertEquals(listOf("/a"), started)

    gate.complete(Unit)
    runCurrent()
    assertEquals(listOf("/a", "/b"), started)
  }

  @Test
  fun anExpiredPageVisitedOfflineIsServedWithNoRefetchAndNoRetry() = runTest {
    val cache = cache()
    expiredPage(cache)
    online = false
    var fetches = 0

    assertEquals(
      "first",
      cache.value("/a") {
        fetches += 1
        throw Unreachable()
      },
    )
    advance(61_000)

    assertEquals(0, fetches)
    assertEquals(listOf("/a"), pushed)
  }

  @Test
  fun aFirstFetchThatFailsThrows() = runTest {
    val cache = cache()
    assertThrows<Unreachable> { cache.value("/a") { throw Unreachable() } }
  }

  @Test
  fun theAgeAPageArrivedWithShortensItsLifetime() = runTest {
    val cache = cache()
    cache.value("/a") { page("first", 60_000, ageMs = 20_000) }

    advance(39_000)
    assertEquals(emptyList<String>(), pushed)
    advance(2_000)

    assertEquals(listOf("/a"), pushed)
  }

  @Test
  fun aPageWhoseAgeUsedUpItsLifetimeIsKeptForTheFloor() = runTest {
    val cache = cache()
    cache.value("/a") { page("first", 60_000, ageMs = 100_000) }

    advance(PageCache.LIFETIME_FLOOR_MS - 1_000)
    assertEquals("first", cache.value("/a") { page("second") })
    assertEquals(emptyList<String>(), pushed)
    advance(2_000)
    assertEquals(listOf("/a"), pushed)
  }

  @Test
  fun aRefetchAnsweredAlreadyExpiredMakesOneRequestAndSetsATimerAtTheFloor() = runTest {
    val cache = cache()
    expiredPage(cache)
    var fetches = 0
    val fetch = suspend {
      fetches += 1
      page("second", 60_000, ageMs = 90_000)
    }

    cache.value("/a", fetch)
    runCurrent()
    assertEquals(listOf("/a"), refreshed)
    assertEquals("second", cache.value("/a", fetch))
    runCurrent()
    pushed.clear()

    assertEquals(1, fetches)
    assertEquals(1, pendingTimers())
    advance(PageCache.LIFETIME_FLOOR_MS - 1_000)
    assertEquals(emptyList<String>(), pushed)
    advance(2_000)
    assertEquals(listOf("/a"), pushed)
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
  fun aRefetchThatFailsInTransitKeepsThePageUnrefreshedAndIsRetriedALifetimeLater() = runTest {
    val cache = cache()
    expiredPage(cache)

    assertEquals("first", cache.value("/a") { throw Unreachable() })
    runCurrent()
    advance(61_000)

    assertEquals(emptyList<String>(), refreshed)
    assertEquals(listOf("/a", "/a"), pushed)
  }

  @Test
  fun aFailingRefetchIsRetriedALifetimeApartForAsLongAsItFails() = runTest {
    val cache = cache()
    expiredPage(cache)

    repeat(5) {
      cache.value("/a") { throw Unreachable() }
      runCurrent()
      advance(61_000)
    }

    assertEquals(6, pushed.size)
  }

  @Test
  fun aPageEvictedDuringItsRefetchIsStoredAndRefreshed() = runTest {
    val cache = cache()
    expiredPage(cache)
    val gate = CompletableDeferred<Unit>()

    cache.value("/a") {
      gate.await()
      page("second", 60_000)
    }
    cache.value("/b") { page("b") }
    cache.value("/c") { page("c") }
    gate.complete(Unit)
    runCurrent()

    assertEquals(listOf("/a"), refreshed)
    assertEquals("second", cache.value("/a") { page("third") })
  }

  @Test
  fun aQueuedRefetchOfARemovedPageNeverFetches() = runTest {
    val cache = cache()
    cache.value("/a") { page("a", 60_000) }
    cache.value("/b") { page("b", 60_000) }
    advance(61_000)
    val gate = CompletableDeferred<Unit>()
    var fetchedB = false

    cache.value("/a") {
      gate.await()
      page("a2")
    }
    cache.value("/b") {
      fetchedB = true
      page("b2")
    }
    runCurrent()
    cache.remove("/b")
    gate.complete(Unit)
    runCurrent()

    assertEquals(false, fetchedB)
  }

  @Test
  fun aCacheWhoseScopeIsCancelledStartsNoLoad() = runTest {
    val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
    val cache = cache(scope)
    cache.value("/a") { page("first", 60_000) }
    advance(61_000)
    scope.cancel()

    cache.revalidate("/a") { page("second") }
    assertThrows<CancellationException> { cache.value("/b") { page("b") } }
  }

  @Test
  fun aPageWhoseRefetchIsRefusedIsDropped() = runTest {
    val cache = cache()
    expiredPage(cache)

    assertEquals("first", cache.value("/a") { throw NotFound() })
    runCurrent()

    assertEquals(0, pendingTimers())
    assertEquals(emptyList<String>(), refreshed)
    assertThrows<NotFound> { cache.value("/a") { throw NotFound() } }
  }

  @Test
  fun aCancelledRefetchKeepsThePageAndRetriesALifetimeLater() = runTest {
    val cache = cache()
    expiredPage(cache)

    cache.value("/a") { throw CancellationException() }
    runCurrent()
    advance(61_000)

    assertEquals(listOf("/a", "/a"), pushed)
    assertEquals("first", cache.value("/a") { page("second", 60_000) })
    runCurrent()
    assertEquals("second", cache.value("/a") { throw Unreachable() })
  }

  @Test
  fun aPageKeptAfterAFailedRefetchIsRefetchedNoSoonerThanALifetimeLater() = runTest {
    val cache = cache()
    expiredPage(cache)
    var fetches = 0
    val failing = suspend {
      fetches += 1
      throw Unreachable()
    }

    cache.value("/a", failing)
    runCurrent()
    advance(59_000)
    cache.value("/a", failing)
    runCurrent()
    assertEquals(1, fetches)

    advance(2_000)
    cache.value("/a", failing)
    runCurrent()
    assertEquals(2, fetches)
  }

  @Test
  fun aFailedRefetchKeepsAnUpdateMadeMeanwhile() = runTest {
    val cache = cache()
    expiredPage(cache)

    cache.value("/a") {
      cache.update { "restamped" }
      throw Unreachable()
    }
    runCurrent()

    assertEquals("restamped", cache.value("/a") { throw Unreachable() })
  }

  @Test
  fun aStalePageRemovedDuringAFailedRefetchIsNotKept() = runTest {
    for (clearingAll in listOf(true, false)) {
      val cache = cache()
      expiredPage(cache)

      cache.value("/a") {
        if (clearingAll) cache.clear() else cache.remove("/a")
        throw Unreachable()
      }
      runCurrent()

      assertEquals("new", cache.value("/a") { page("new") })
    }
  }

  @Test
  fun aFetchThatOutlivesARemovalCachesNothing() = runTest {
    for (clearingAll in listOf(true, false)) {
      val cache = cache()
      val old =
        cache.value("/a") {
          if (clearingAll) cache.clear() else cache.remove("/a")
          page("old")
        }

      assertEquals("old", old)
      assertEquals("new", cache.value("/a") { page("new") })
    }
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
    expiredPage(cache)
    cache.value("/a") { page("second", 60_000) }
    runCurrent()
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
}
