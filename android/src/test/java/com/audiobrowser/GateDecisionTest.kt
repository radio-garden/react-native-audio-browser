package com.audiobrowser

import com.margelo.nitro.audiobrowser.Gate
import com.margelo.nitro.audiobrowser.GateDecision
import com.margelo.nitro.audiobrowser.GateReason
import com.margelo.nitro.audiobrowser.NativeGateRequest
import com.margelo.nitro.core.Promise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock

class GateDecisionTest {

  // AudioBrowser's constructor and a Nitro Promise both need JNI, so the browser is a mock that
  // runs its real methods, with an active gate written to the field setGate writes, and the
  // resolver throws where awaiting its promise would.
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
      // A Nitro promise's await ignores cancellation, so JS answers anyway; here, by rejecting.
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
}
