package com.audiobrowser.player

import com.audiobrowser.browser.HttpStatusException
import com.audiobrowser.defaultFormattedError
import com.audiobrowser.formattedOrDefault
import com.audiobrowser.navigationErrorFor
import com.audiobrowser.util.BrowserPathHelper
import com.margelo.nitro.audiobrowser.FormattedNavigationError
import com.margelo.nitro.audiobrowser.NavigationError
import com.margelo.nitro.audiobrowser.NavigationErrorType
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the tiles an Android Auto browse level serves in place of a list: the offline and failure
 * tiles, worded by the app's `formatNavigationError` (ADR 0001). MediaSessionCallback itself needs
 * a live MediaLibrarySession, so the pieces under test are the ones carved out of it: the tile
 * builders, [navigationErrorFor] (the one exception mapping browse and search share) and
 * [formattedOrDefault] (the formatter hop's fallback rule, which sits off the JNI-backed Promise
 * edge for exactly this reason).
 */
@RunWith(RobolectricTestRunner::class)
class BrowseTilesTest {

  /**
   * Stands in for an app's `formatNavigationError`: different copy per error and per path, the
   * point of ADR 0001. The search branches match the prefix the library builds car search paths
   * from.
   */
  private fun appCopy(error: NavigationError, path: String): FormattedNavigationError {
    val isSearch = path.startsWith(BrowserPathHelper.SEARCH_PATH_PREFIX)
    return when {
      error.code == NavigationErrorType.NETWORK_ERROR && isSearch ->
        FormattedNavigationError("Search needs a connection", "Reconnect and try again.")
      error.code == NavigationErrorType.NETWORK_ERROR ->
        FormattedNavigationError("You're offline", "Check your connection.")
      else -> FormattedNavigationError("We couldn't load that", "Try again in a moment.")
    }
  }

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
  fun `the offline tile is worded per path, and title-only by default`() {
    val browseTile = createOfflineMediaItem(appCopy(offlineError(), "/favorites"))
    assertEquals("You're offline", browseTile.mediaMetadata.title)
    assertEquals("Check your connection.", browseTile.mediaMetadata.subtitle)

    val default = defaultFormattedError(offlineError())
    assertEquals("Network Error", default.title)
    assertNull(default.message)
    assertEquals("", createOfflineMediaItem(default).mediaMetadata.subtitle)
  }

  @Test
  fun `the browse-error tile is worded by the error its exception mapped to`() {
    val failure = navigationErrorFor(HttpStatusException(503, "Service Unavailable"))

    val appTile = createBrowseErrorMediaItem(appCopy(failure, "/favorites"))
    assertEquals(BrowserPathHelper.ERROR_PATH, appTile.mediaId)
    assertEquals("We couldn't load that", appTile.mediaMetadata.title)
    assertEquals("Try again in a moment.", appTile.mediaMetadata.subtitle)

    // Without a formatter, the status text the in-app error already shows.
    val defaultTile = createBrowseErrorMediaItem(defaultFormattedError(failure))
    assertEquals("Service Unavailable", defaultTile.mediaMetadata.title)

    // A re-read of the tile has no exception left to map, so it stands on the generic failure.
    assertEquals("Error", defaultFormattedError(browseFailureError()).title)
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
