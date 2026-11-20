package com.audiobrowser.util

import com.margelo.nitro.core.Promise
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AwaitCancellableTest {

  @Test
  fun `an answer resumes the await with its value`() =
    runTest(timeout = 5.seconds) {
      val js = JsPromise<String>()
      val result = backgroundScope.async { js.promise.awaitCancellable() }
      runCurrent()

      js.resolve("answer")
      runCurrent()

      assertTrue(result.isCompleted)
      assertEquals("answer", result.await())
    }

  @Test
  fun `a rejection resumes the await with its error`() =
    runTest(timeout = 5.seconds) {
      val js = JsPromise<String>()
      val result = backgroundScope.async { runCatching { js.promise.awaitCancellable() } }
      runCurrent()

      js.reject(IllegalStateException("rejected"))
      runCurrent()

      assertTrue(result.isCompleted)
      assertEquals("rejected", result.await().exceptionOrNull()?.message)
    }

  @Test
  fun `a cancelled caller stops waiting and ignores a later answer`() =
    runTest(timeout = 5.seconds) {
      val js = JsPromise<String>()
      var after = false
      val caller =
        backgroundScope.async {
          js.promise.awaitCancellable()
          after = true
        }
      runCurrent()

      caller.cancel()
      runCurrent()
      assertTrue(caller.isCompleted)
      js.resolve("late")
      runCurrent()

      assertFalse(after)
    }

  @Test
  fun `a promise that never settles fails at its timeout`() =
    runTest(timeout = 5.seconds) {
      val js = JsPromise<String>()
      val result = backgroundScope.async { runCatching { js.promise.awaitOrTimeout() } }

      advanceTimeBy(FALLBACK_TIMEOUT - 1.seconds)
      runCurrent()
      assertFalse(result.isCompleted)
      advanceTimeBy(2.seconds)
      runCurrent()

      assertTrue(result.isCompleted)
      val error = result.await().exceptionOrNull()
      assertTrue(error is TimeoutException)
      assertFalse(error is CancellationException)
    }

  @Test
  fun `a nested promise's timeout covers both layers`() =
    runTest(timeout = 5.seconds) {
      val outer = JsPromise<Promise<String>>()
      val inner = JsPromise<String>()
      val result = backgroundScope.async { runCatching { outer.promise.awaitNestedOrTimeout() } }
      runCurrent()

      advanceTimeBy(FALLBACK_TIMEOUT / 2)
      outer.resolve(inner.promise)
      advanceTimeBy(FALLBACK_TIMEOUT / 2 + 1.seconds)
      runCurrent()

      assertTrue(result.isCompleted)
      assertTrue(result.await().exceptionOrNull() is TimeoutException)
    }

  @Test
  fun `a nested promise answers with the inner value`() =
    runTest(timeout = 5.seconds) {
      val outer = JsPromise<Promise<String>>()
      val inner = JsPromise<String>()
      val result = backgroundScope.async { outer.promise.awaitNestedOrTimeout() }
      runCurrent()

      outer.resolve(inner.promise)
      runCurrent()
      inner.resolve("answer")
      runCurrent()

      assertTrue(result.isCompleted)
      assertEquals("answer", result.await())
    }

  @Test
  fun `an outer timeout keeps its cancellation`() =
    runTest(timeout = 5.seconds) {
      val js = JsPromise<String>()
      val result =
        backgroundScope.async {
          runCatching { withTimeoutOrNull(FALLBACK_TIMEOUT / 2) { js.promise.awaitOrTimeout() } }
        }

      advanceTimeBy(FALLBACK_TIMEOUT / 2 + 1.seconds)
      runCurrent()

      assertTrue(result.isCompleted)
      assertEquals(Result.success(null), result.await())
    }

  @Test
  fun `a nested promise's rejection leaves out Nitro's error name`() =
    runTest(timeout = 5.seconds) {
      assertEquals("token expired", nestedRejection("Error: token expired"))
      assertEquals("bad header", nestedRejection("TypeError: bad header"))
      assertEquals("upstream: 503", nestedRejection("Error: upstream: 503"))
    }

  @Test
  fun `a nested promise's rejection keeps a name that is not an error name`() =
    runTest(timeout = 5.seconds) {
      assertEquals("Timeout: took 30s", nestedRejection("Timeout: took 30s"))
      assertEquals("ErrorCode: 7", nestedRejection("ErrorCode: 7"))
    }

  @Test
  fun `a throw before the nested promise keeps its message whole`() =
    runTest(timeout = 5.seconds) {
      val outer = JsPromise<Promise<String>>()
      outer.reject(RuntimeException("Error: thrown\n\nwith a blank line"))
      val message = runCatching { outer.promise.awaitNested() }.exceptionOrNull()?.message
      assertEquals("Error: thrown\n\nwith a blank line", message)
    }

  @Test
  fun `a JS promise's rejection leaves out Nitro's error name`() =
    runTest(timeout = 5.seconds) {
      val js = JsPromise<String>().apply { reject(RuntimeException("RangeError: too far")) }
      assertEquals(
        "too far",
        runCatching { js.promise.awaitJsPromise() }.exceptionOrNull()?.message,
      )
    }

  private suspend fun nestedRejection(what: String): String? {
    val inner = JsPromise<String>().apply { reject(RuntimeException(what)) }
    val outer = JsPromise<Promise<String>>().apply { resolve(inner.promise) }
    return runCatching { outer.promise.awaitNested() }.exceptionOrNull()?.message
  }
}
