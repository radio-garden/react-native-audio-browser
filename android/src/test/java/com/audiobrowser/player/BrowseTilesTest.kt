package com.audiobrowser.player

import com.audiobrowser.formattedOrDefault
import com.margelo.nitro.audiobrowser.FormattedNavigationError
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the tiles an Android Auto browse level serves in place of a list, and the pieces they are
 * worded from. MediaSessionCallback itself needs a live MediaLibrarySession, so what is under test
 * are the pieces carved out of it: [formattedOrDefault] (the formatter hop's fallback rule, which
 * sits off the JNI-backed Promise edge for exactly this reason).
 */
@RunWith(RobolectricTestRunner::class)
class BrowseTilesTest {

  @Test
  fun `the formatter hop keeps the app's answer`() = runTest {
    val custom = FormattedNavigationError("No favorites yet", "Tap the heart.")
    assertEquals(
      custom,
      formattedOrDefault(backgroundScope, FormattedNavigationError("Nothing here", null)) { custom },
    )
  }

  @Test
  fun `the formatter hop falls back to the default on null and on a throw`() = runTest {
    val default = FormattedNavigationError("Nothing here", null)

    assertEquals(default, formattedOrDefault(backgroundScope, default) { null })
    assertEquals(
      default,
      formattedOrDefault(backgroundScope, default) { throw IllegalStateException("boom") },
    )
  }

  @Test
  fun `a formatter parked in a non-cancellable wait falls back without hanging`() = runTest {
    val default = FormattedNavigationError("Nothing here", null)
    var parked: Continuation<FormattedNavigationError?>? = null

    try {
      // Nitro's Promise.await parks exactly like this — suspendCoroutine, so cancelling the
      // coroutine never unparks it. The hop must be abandoned, not waited on.
      val formatted =
        formattedOrDefault(backgroundScope, default) {
          suspendCoroutine { continuation -> parked = continuation }
        }

      assertNotNull(parked)
      assertEquals(default, formatted)
    } finally {
      parked?.resume(null)
    }
  }
}
