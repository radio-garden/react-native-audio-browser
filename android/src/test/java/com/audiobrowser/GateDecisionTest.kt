package com.audiobrowser

import com.audiobrowser.util.FALLBACK_TIMEOUT
import com.audiobrowser.util.JsPromise
import com.margelo.nitro.audiobrowser.Gate
import com.margelo.nitro.audiobrowser.GateDecision
import com.margelo.nitro.audiobrowser.GateReason
import com.margelo.nitro.audiobrowser.NativeGateRequest
import com.margelo.nitro.core.Promise
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock

class GateDecisionTest {

  // AudioBrowser's constructor needs JNI, so the browser is a mock that runs its real methods,
  // with an active gate written to the field setGate writes.
  private fun gatedBrowser(
    resolveGate: (NativeGateRequest) -> Promise<Promise<GateDecision>>
  ): AudioBrowser {
    val browser = mock(AudioBrowser::class.java, CALLS_REAL_METHODS)
    val gateState =
      Class.forName("com.audiobrowser.AudioBrowser\$GateState")
        .getDeclaredConstructor(Boolean::class.java, Gate::class.java, Boolean::class.java)
        .apply { isAccessible = true }
        .newInstance(true, Gate("Gated", null), true)
    AudioBrowser::class
      .java
      .getDeclaredField("gateState")
      .apply { isAccessible = true }
      .set(browser, gateState)
    browser.resolveGate = resolveGate
    return browser
  }

  private val request = NativeGateRequest(GateReason.SEARCH, null, null)

  @Test
  fun `a decision whose caller is cancelled while it waits returns nothing`() = runTest {
    var outcome: AudioBrowser.GateOutcome? = null
    lateinit var caller: Job
    val browser = gatedBrowser {
      caller.cancel()
      // Cancelled while the resolver runs, which then fails.
      throw RuntimeException("Rejected")
    }

    caller = launch { outcome = browser.gateDecision(request) }
    caller.join()

    assertNull(outcome)
  }

  @Test
  fun `a resolver's own cancellation fails closed while its caller is active`() = runTest {
    val browser = gatedBrowser { throw CancellationException("Timed out") }

    assertTrue(browser.gateDecision(request).gated)
  }

  /** A gated browser whose resolver's JS promise the test settles. */
  private fun pendingResolver(): Pair<AudioBrowser, JsPromise<GateDecision>> {
    val decision = JsPromise<GateDecision>()
    val bridge = JsPromise<Promise<GateDecision>>().apply { resolve(decision.promise) }
    return gatedBrowser { bridge.promise } to decision
  }

  @Test
  fun `a resolver answering inside the timeout decides`() =
    runTest(timeout = 5.seconds) {
      val (browser, decision) = pendingResolver()
      val outcome = backgroundScope.async { browser.gateDecision(request) }
      runCurrent()

      advanceTimeBy(FALLBACK_TIMEOUT - 1.seconds)
      decision.resolve(GateDecision(false, null))
      runCurrent()

      assertTrue(outcome.isCompleted)
      assertFalse(outcome.await().gated)
    }

  @Test
  fun `a resolver that never answers fails closed at the timeout`() =
    runTest(timeout = 5.seconds) {
      val (browser, _) = pendingResolver()
      val outcome = backgroundScope.async { browser.gateDecision(request) }

      advanceTimeBy(FALLBACK_TIMEOUT - 1.seconds)
      runCurrent()
      assertFalse(outcome.isCompleted)
      advanceTimeBy(2.seconds)
      runCurrent()

      assertTrue(outcome.isCompleted)
      assertTrue(outcome.await().gated)
    }

  @Test
  fun `a caller cancelled while the resolver is silent stops waiting`() =
    runTest(timeout = 5.seconds) {
      val (browser, _) = pendingResolver()
      var returned = false
      val outcome =
        backgroundScope.launch {
          browser.gateDecision(request)
          returned = true
        }
      runCurrent()

      outcome.cancel()
      runCurrent()

      assertTrue(outcome.isCompleted)
      assertFalse(returned)
    }
}
