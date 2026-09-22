package com.audiobrowser.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.audiobrowser.NavigationErrorException
import com.audiobrowser.TestFixtures
import com.audiobrowser.browser.HttpStatusException
import com.audiobrowser.browser.NetworkException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the tiles an Android Auto browse level serves in place of a list: the empty tile of a page
 * that resolved with zero children, and the offline and failure tiles, all worded by the app's
 * `formatNavigationError` (ADR 0001). MediaSessionCallback itself needs a live MediaLibrarySession,
 * so the pieces under test are the ones carved out of it: [browseLevelItems] (the empty-branch
 * decision), the tile builders, [BrowserPathHelper.isDeadEndPath] (the drill-in guard),
 * [announcedSearchCount] (what onSearch tells the controller to ask for), [FailedSearchSlot] (a
 * failed search, which must not read as an empty one), [OfflineSearchRegistry] (the searches
 * connectivity returning re-runs), [navigationErrorFor] (the one exception mapping browse, search
 * and the JS-facing `search` rejection share) and [formattedOrDefault] (the formatter hop's
 * fallback rule, which sits off the JNI-backed Promise edge for exactly this reason).
 */
@RunWith(RobolectricTestRunner::class)
class BrowseTilesTest {

  private fun controller(packageName: String, uid: Int): MediaSession.ControllerInfo =
    MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      packageName,
      /* pid= */ 0,
      uid,
      /* libraryVersion= */ 2,
      /* interfaceVersion= */ 2,
      /* trusted= */ true,
      Bundle.EMPTY,
      /* isPackageNameVerified= */ true,
    )

  private fun track(title: String): MediaItem =
    MediaItem.Builder()
      .setMediaId("/station/$title")
      .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
      .build()

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
      error.code != NavigationErrorType.EMPTY_CONTENT ->
        FormattedNavigationError("We couldn't load that", "Try again in a moment.")
      path == "/favorites" ->
        FormattedNavigationError("No favorites yet", "Tap the heart on a station to add it here.")
      isSearch -> FormattedNavigationError("No results found", "Try a different search.")
      else -> FormattedNavigationError("Nothing here", null)
    }
  }

  @Test
  fun `an empty favorites page serves one tile carrying the app's copy for that path`() = runTest {
    val items = browseLevelItems(emptyList()) { appCopy(emptyContentError(), "/favorites") }

    assertEquals(1, items.size)
    val tile = items.single()
    assertEquals(BrowserPathHelper.EMPTY_PATH, tile.mediaId)
    assertEquals("No favorites yet", tile.mediaMetadata.title)
    assertEquals("Tap the heart on a station to add it here.", tile.mediaMetadata.subtitle)
    assertFalse(tile.mediaMetadata.isBrowsable!!)
    assertFalse(tile.mediaMetadata.isPlayable!!)
  }

  @Test
  fun `with no formatter the tile carries the neutral default`() = runTest {
    val default = defaultFormattedError(emptyContentError())
    assertEquals("Nothing here", default.title)
    assertNull(default.message)

    val tile = browseLevelItems(emptyList()) { default }.single()
    assertEquals("Nothing here", tile.mediaMetadata.title)
    assertEquals("", tile.mediaMetadata.subtitle)
  }

  @Test
  fun `a multi-line message collapses to the single-line subtitle`() = runTest {
    val tile =
      browseLevelItems(emptyList()) { FormattedNavigationError("Empty", "One\nTwo") }.single()
    assertEquals("One Two", tile.mediaMetadata.subtitle)
  }

  @Test
  fun `a page with children is served unchanged and never asks for copy`() = runTest {
    val children = listOf(track("a"), track("b"))

    val items = browseLevelItems(children) { error("formatter must not run for a full page") }

    assertSame(children, items)
  }

  @Test
  fun `a drill-in on the empty tile dead-ends`() {
    assertTrue(BrowserPathHelper.isDeadEndPath(BrowserPathHelper.EMPTY_PATH))
    assertFalse(BrowserPathHelper.isDeadEndPath("/favorites"))
    // The gate tile re-serves its message instead of dead-ending.
    assertFalse(BrowserPathHelper.isDeadEndPath(BrowserPathHelper.GATE_PATH))
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
  fun `an empty search serves one tile carrying the app's copy for the search path`() = runTest {
    val searchPath = BrowserPathHelper.createSearchPath("jazz")

    val items = browseLevelItems(emptyList()) { appCopy(emptyContentError(), searchPath) }

    assertEquals(1, items.size)
    val tile = items.single()
    assertEquals(BrowserPathHelper.EMPTY_PATH, tile.mediaId)
    assertEquals("No results found", tile.mediaMetadata.title)
    assertEquals("Try a different search.", tile.mediaMetadata.subtitle)
    assertFalse(tile.mediaMetadata.isBrowsable!!)
    assertFalse(tile.mediaMetadata.isPlayable!!)
  }

  @Test
  fun `a search with hits is served unchanged and never asks for copy`() = runTest {
    val hits = listOf(track("Jazz FM"), track("Jazz24"))

    val items = browseLevelItems(hits) { error("formatter must not run for a search with hits") }

    assertSame(hits, items)
  }

  @Test
  fun `a search whose every hit is disabled counts as empty and announces the tile`() {
    val disabledOnly =
      listOf(
        TestFixtures.section(
          children =
            arrayOf(
              TestFixtures.track(title = "Gone", disabled = true),
              TestFixtures.track(title = "Also gone", disabled = true),
            )
        )
      )

    // Nothing servable, so the announced count is the tile's — a controller told zero would never
    // come back for it.
    assertEquals(1, announcedSearchCount(disabledOnly))
  }

  @Test
  fun `a search announces only its servable hits`() {
    val mixed =
      listOf(
        TestFixtures.section(
          children =
            arrayOf(
              TestFixtures.track(title = "Playable"),
              TestFixtures.track(title = "Gone", disabled = true),
            )
        ),
        TestFixtures.section(children = arrayOf(TestFixtures.track(title = "Another"))),
      )

    assertEquals(2, announcedSearchCount(mixed))
    assertEquals(1, announcedSearchCount(null))
  }

  @Test
  fun `an offline search serves the offline tile with the app's copy for the search path`() {
    val searchPath = BrowserPathHelper.createSearchPath("jazz")
    val slot = FailedSearchSlot()

    val tile = createOfflineMediaItem(appCopy(offlineError(), searchPath))

    assertEquals(BrowserPathHelper.OFFLINE_PATH, tile.mediaId)
    assertEquals("Search needs a connection", tile.mediaMetadata.title)
    assertEquals("Reconnect and try again.", tile.mediaMetadata.subtitle)
    assertFalse(tile.mediaMetadata.isBrowsable!!)
    assertFalse(tile.mediaMetadata.isPlayable!!)
    assertTrue(BrowserPathHelper.isDeadEndPath(BrowserPathHelper.OFFLINE_PATH))
    // Offline is not a search that failed: connectivity returning is what serves results again,
    // so the slot stays empty and a later re-query of the query is not a failure.
    assertNull(slot.errorFor("jazz"))
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
  fun `a recorded search failure carries its error for that query only, until a success clears it`() {
    val slot = FailedSearchSlot()
    val failure = navigationErrorFor(HttpStatusException(500, "Internal Server Error"))
    assertNull(slot.errorFor("jazz"))

    slot.record("jazz", failure)
    assertEquals(failure, slot.errorFor("jazz"))
    assertNull(slot.errorFor("blues"))

    slot.clear()
    assertNull(slot.errorFor("jazz"))
  }

  @Test
  fun `a failed search rejects with the navigation error a failed browse would raise`() {
    // Nitro's Promise is JNI-backed (its constructor calls initHybrid), so the JS-facing
    // AudioBrowser.search cannot be built here; what it rejects with is this.
    val rejection =
      NavigationErrorException(navigationErrorFor(HttpStatusException(503, "Service Unavailable")))
    assertEquals(NavigationErrorType.HTTP_ERROR, rejection.error.code)
    assertEquals(503.0, rejection.error.statusCode!!, 0.0)
    assertEquals(false, rejection.error.statusCodeSuccess)
    assertEquals("Service Unavailable", rejection.message)

    assertEquals(
      NavigationErrorType.NETWORK_ERROR,
      navigationErrorFor(NetworkException("Network request failed")).code,
    )

    val unknown = navigationErrorFor(IllegalStateException())
    assertEquals(NavigationErrorType.UNKNOWN_ERROR, unknown.code)
    assertEquals("An unexpected error occurred", unknown.message)
  }

  @Test
  fun `an offline search is remembered per controller, latest query only`() {
    val registry = OfflineSearchRegistry()
    val auto = controller("com.google.android.projection.gearhead", uid = 10)
    val assistant = controller("com.google.android.googlequicksearchbox", uid = 11)

    registry.record(auto, "jazz", null)
    registry.record(assistant, "blues", null)
    registry.record(auto, "jazz fm", null)

    val pending = registry.drain()
    assertEquals(2, pending.size)
    assertEquals("jazz fm", pending.single { it.browser == auto }.query)
    assertEquals("blues", pending.single { it.browser == assistant }.query)
    // Drained, so a second connectivity change does not re-run the same searches again.
    assertTrue(registry.drain().isEmpty())
  }

  @Test
  fun `a re-run search is announced by its hits, an offline one by its tile`() {
    val hits =
      listOf(
        TestFixtures.section(
          children =
            arrayOf(TestFixtures.track(title = "Jazz FM"), TestFixtures.track(title = "Jazz24"))
        )
      )

    // The re-run goes through the same count as the first, online search, so what a controller is
    // told to fetch is what onGetSearchResult then serves from the cache that search filled.
    assertEquals(2, announcedSearchCount(hits))
    // While offline there is nothing to count: the one tile is announced instead.
    assertEquals(1, announcedSearchCount(null))
  }

  @Test
  fun `a disconnected controller's offline search is forgotten`() {
    val registry = OfflineSearchRegistry()
    val auto = controller("com.google.android.projection.gearhead", uid = 10)
    val assistant = controller("com.google.android.googlequicksearchbox", uid = 11)
    registry.record(auto, "jazz", null)
    registry.record(assistant, "blues", null)

    registry.forget(auto)

    assertEquals(listOf("blues"), registry.drain().map { it.query })
  }

  @Test
  fun `a remembered search keeps the params its controller asked with`() {
    val registry = OfflineSearchRegistry()
    val auto = controller("com.google.android.projection.gearhead", uid = 10)
    val params =
      MediaLibraryService.LibraryParams.Builder()
        .setExtras(Bundle().apply { putInt("page", 3) })
        .build()

    registry.record(auto, "jazz", params)

    assertEquals(3, registry.drain().single().params?.extras?.getInt("page"))
  }

  @Test
  fun `the failure sentinel is distinct from the empty one and dead-ends too`() {
    assertNotEquals(BrowserPathHelper.EMPTY_PATH, BrowserPathHelper.ERROR_PATH)
    assertTrue(BrowserPathHelper.isDeadEndPath(BrowserPathHelper.ERROR_PATH))
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
