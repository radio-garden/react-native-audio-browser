package com.audiobrowser.player

import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.margelo.nitro.audiobrowser.NavigationError

/**
 * The search each controller was last served the offline tile for. Media3 keeps no record of a
 * search and it has no subscription, so without this the offline tile stays until the user searches
 * again. Written from the IO scope and read from the connectivity observer.
 */
internal class OfflineSearchRegistry {
  private val searches = mutableMapOf<MediaSession.ControllerInfo, OfflineSearch>()

  @Synchronized
  fun record(
    browser: MediaSession.ControllerInfo,
    query: String,
    params: MediaLibraryService.LibraryParams?,
  ) {
    searches[browser] = OfflineSearch(browser, query, params)
  }

  @Synchronized
  fun forget(browser: MediaSession.ControllerInfo) {
    searches.remove(browser)
  }

  /** Takes every record: a re-run that finds the network gone again records its own. */
  @Synchronized
  fun drain(): List<OfflineSearch> = searches.values.toList().also { searches.clear() }
}

internal data class OfflineSearch(
  val browser: MediaSession.ControllerInfo,
  val query: String,
  val params: MediaLibraryService.LibraryParams?,
)

/**
 * The query whose search threw, so [MediaSessionCallback.onGetSearchResult] serves the error tile
 * rather than the empty one. One slot, like BrowserManager's single-entry result cache: a late
 * fetch of an earlier failed query serves the empty tile.
 */
internal class FailedSearchSlot {
  @Volatile private var failed: Pair<String, NavigationError>? = null

  fun record(query: String, error: NavigationError) {
    failed = query to error
  }

  fun clear() {
    failed = null
  }

  fun errorFor(query: String): NavigationError? = failed?.takeIf { it.first == query }?.second
}
