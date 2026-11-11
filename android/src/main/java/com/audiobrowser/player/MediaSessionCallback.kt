package com.audiobrowser.player

import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Rating
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.browser.handleTrackLoad
import com.audiobrowser.browser.normalizedSections
import com.audiobrowser.browser.styleResolvedSections
import com.audiobrowser.browser.untitledSection
import com.audiobrowser.extension.indexOfTappedTrack
import com.audiobrowser.extension.toTrack
import com.audiobrowser.navigationErrorFor
import com.audiobrowser.util.Automotive
import com.audiobrowser.util.BrowserPathHelper
import com.audiobrowser.util.RatingFavorites
import com.audiobrowser.util.TrackFactory
import com.audiobrowser.util.isAutomotive
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.margelo.nitro.audiobrowser.FormattedNavigationError
import com.margelo.nitro.audiobrowser.Gate
import com.margelo.nitro.audiobrowser.GateEvent
import com.margelo.nitro.audiobrowser.GateReason
import com.margelo.nitro.audiobrowser.MediaReference
import com.margelo.nitro.audiobrowser.NativeGateRequest
import com.margelo.nitro.audiobrowser.NavigationError
import com.margelo.nitro.audiobrowser.NavigationErrorType
import com.margelo.nitro.audiobrowser.PlayerCapabilities
import com.margelo.nitro.audiobrowser.RemoteButtonLayout
import com.margelo.nitro.audiobrowser.SearchParams
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Everything the MediaLibrarySession serves except transport, which reaches the player through
 * [InterceptingPlayer]; Assistant's play-from-search arrives as an intent in the service. Callbacks
 * answering through [scope] run on IO, so anything touching ExoPlayer hops to [Dispatchers.Main];
 * the rest answer on the Media3 application thread.
 */
class MediaSessionCallback(private val player: Player) :
  MediaLibraryService.MediaLibrarySession.Callback {
  internal val commandManager = MediaSessionCommandManager(player.context.isAutomotive)
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  // Written on the Media3 application thread, read from the network observer and JS-triggered
  // notifies: every access goes through synchronized(this).
  private val parentIdSubscriptions =
    mutableMapOf<String, MutableSet<MediaSession.ControllerInfo>>()
  private val failedSearch = FailedSearchSlot()
  private val offlineSearches = OfflineSearchRegistry()
  private var mediaLibrarySession: MediaLibraryService.MediaLibrarySession? = null

  /** The art size the last browser hinted in onGetLibraryRoot, or null before any hint. */
  @Volatile
  var artworkSizeHintPixels: Int? = null
    private set

  init {
    player.networkMonitor.observeOnline(scope) { online ->
      notifySubscribedChildrenChanged()
      // A browse level is re-queried through its subscription; a search has none, so its offline
      // tile leaves the screen only when the query runs again.
      if (online) scope.launch { reannounceOfflineSearches() }
    }
  }

  /** Media3's legacy browse bridge asks for a whole list with pageSize [Int.MAX_VALUE]. */
  private fun <T> List<T>.paginate(page: Int, pageSize: Int): List<T> {
    return if (pageSize in 1 until Int.MAX_VALUE) {
      this.drop(page * pageSize).take(pageSize)
    } else {
      this
    }
  }

  // Car search surfaces give free text only. Mirrors BrowserManager's query→SearchParams.
  private fun searchParams(query: String): SearchParams =
    SearchParams(
      mode = null,
      query = query,
      genre = null,
      artist = null,
      album = null,
      title = null,
      playlist = null,
      reference = MediaReference.UNKNOWN,
    )

  private fun createGateMediaItem(gate: Gate): MediaItem =
    createErrorMediaItem(
      BrowserPathHelper.GATE_PATH,
      FormattedNavigationError(gate.title, gate.message),
    )

  fun updateMediaSession(
    mediaSession: MediaSession,
    capabilities: PlayerCapabilities,
    remoteButtonLayout: RemoteButtonLayout?,
    searchAvailable: Boolean,
    forwardJumpInterval: Double,
    backwardJumpInterval: Double,
  ) {
    this.mediaLibrarySession = mediaSession as? MediaLibraryService.MediaLibrarySession
    commandManager.updateMediaSession(
      mediaSession,
      capabilities,
      remoteButtonLayout,
      searchAvailable,
      forwardJumpInterval,
      backwardJumpInterval,
    )
  }

  override fun onConnect(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
  ): MediaSession.ConnectionResult {
    Timber.Forest.d("MediaSession connect: ${controller.packageName}")
    return commandManager.buildConnectionResult(session)
  }

  override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
    Timber.Forest.d("MediaSession disconnect: ${controller.packageName}")
    // Android Auto normally leaves without unsubscribing; the controller would be notified, and
    // held, forever.
    offlineSearches.forget(controller)
    synchronized(this) {
      parentIdSubscriptions.values.forEach { it.remove(controller) }
      parentIdSubscriptions.entries.removeAll { (_, browsers) -> browsers.isEmpty() }
    }
    super.onDisconnected(session, controller)
  }

  override fun onCustomCommand(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
    command: SessionCommand,
    args: Bundle,
  ): ListenableFuture<SessionResult> {
    // A success makes the controller flip its heart optimistically, so a no-op (no current track)
    // reports INVALID_STATE rather than success-then-revert.
    if (command.customAction == MediaSessionCommandManager.CUSTOM_ACTION_FAVORITE) {
      val applied = player.toggleActiveTrackFavorited()
      Timber.d("Favorite button tapped - toggle applied=$applied")
      return Futures.immediateFuture(
        SessionResult(
          if (applied) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_INVALID_STATE
        )
      )
    }

    if (commandManager.handleCustomCommand(command, player)) {
      return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
    return super.onCustomCommand(session, controller, command, args)
  }

  override fun onSetRating(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
    rating: Rating,
  ): ListenableFuture<SessionResult> {
    // A heart rating sets the now-playing favorite, as the favorite button does; INVALID_STATE when
    // there is no current track.
    RatingFavorites.favoritedFor(rating)?.let { favorited ->
      val applied = player.setActiveTrackFavorited(favorited)
      return Futures.immediateFuture(
        SessionResult(
          if (applied) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_INVALID_STATE
        )
      )
    }
    return super.onSetRating(session, controller, rating)
  }

  /**
   * A legacy controller's setRating names the current item; a Media3 controller's can name any
   * item. Only the current one can be rated.
   */
  override fun onSetRating(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaId: String,
    rating: Rating,
  ): ListenableFuture<SessionResult> =
    if (mediaId == session.player.currentMediaItem?.mediaId) {
      onSetRating(session, controller, rating)
    } else {
      super.onSetRating(session, controller, mediaId, rating)
    }

  @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
  override fun onGetLibraryRoot(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    params: MediaLibraryService.LibraryParams?,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    params?.extras?.getInt(MediaConstants.EXTRAS_KEY_MEDIA_ART_SIZE_PIXELS, 0)?.let { size ->
      if (size > 0) {
        artworkSizeHintPixels = size
        Timber.d("Received artwork size hint: ${size}px from ${browser.packageName}")
      }
    }

    if (params?.isRecent == true) {
      // Media3 answers System UI's EXTRA_RECENT root itself, through onPlaybackResumption, so this
      // is another browser asking; there is no browsable recents tree.
      return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))
    }

    Timber.Forest.d("onGetLibraryRoot: { package: ${browser.packageName} }")
    return Futures.immediateFuture(
      LibraryResult.ofItem(
        MediaItem.Builder()
          .setMediaId(BrowserPathHelper.ROOT_PATH)
          .setMediaMetadata(
            MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build()
          )
          .build(),
        null,
      )
    )
  }

  override fun onGetChildren(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    parentId: String,
    page: Int,
    pageSize: Int,
    params: MediaLibraryService.LibraryParams?,
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    Timber.d(
      "onGetChildren: {parentId: $parentId, page: $page, pageSize: $pageSize, isSpecialPath: ${BrowserPathHelper.isSpecialPath(parentId)} }"
    )
    return scope.future {
      children(session, browser, parentId, page, pageSize, params).also {
        if (parentId == BrowserPathHelper.ROOT_PATH && page == 0) {
          rootTile = deadEndTileOf(it.value.orEmpty())
        }
      }
    }
  }

  /**
   * The tile the root last served in place of its tabs, or null. Android Automotive OS shows such a
   * root as one tab that drills into the tile's sentinel path (see [deadEndChildren]).
   */
  @Volatile private var rootTile: MediaItem? = null

  private suspend fun children(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    parentId: String,
    page: Int,
    pageSize: Int,
    params: MediaLibraryService.LibraryParams?,
  ): LibraryResult<ImmutableList<MediaItem>> {
    val audioBrowser =
      player.awaitBrowser().also { Timber.d("Browser ready, proceeding with onGetChildren") }
    val browserManager = audioBrowser.browserManager

    // While a gate is active the root keeps its tabs; a gated level, the gate tile's own sentinel
    // included, serves the gate tile, so drilling into it re-shows the message, and an allowed one
    // falls through. No SessionError: a gate is app state, not a failure. Before the offline
    // guard: connectivity does not ungate content.
    if (parentId != BrowserPathHelper.ROOT_PATH) {
      val outcome =
        audioBrowser.gateDecision(
          NativeGateRequest(reason = GateReason.BROWSE, path = parentId, search = null)
        )
      if (outcome.gated) {
        audioBrowser.onGate(GateEvent(GateReason.BROWSE))
        // gateDecision always sets chrome on a gated outcome.
        return LibraryResult.ofItemList(
          ImmutableList.of(createGateMediaItem(outcome.chrome!!)),
          params,
        )
      }
    }

    // A tap on the offline tile re-sends the alert, but only while still offline:
    // notifyChildrenChanged re-queries these paths with no user action, e.g. when connectivity
    // returns.
    if (BrowserPathHelper.isDeadEndPath(parentId)) {
      val offline = !player.networkMonitor.isOnline.value
      if (parentId == BrowserPathHelper.OFFLINE_PATH && offline) {
        sendBrowseError(session, browser, offline = true)
      }
      return LibraryResult.ofItemList(
        deadEndChildren(parentId, rootTile, Automotive.device, offline),
        params,
      )
    }

    if (
      !player.networkMonitor.isOnline.value && browserManager.config.androidControllerOfflineError
    ) {
      Timber.w("Network offline - returning error message for: $parentId")
      sendBrowseError(session, browser, offline = true)
      return LibraryResult.ofItemList(
        ImmutableList.of(
          createOfflineMediaItem(audioBrowser.resolveFormattedError(offlineError(), parentId))
        ),
        params,
      )
    }

    return try {
      val children =
        if (parentId == BrowserPathHelper.ROOT_PATH) {
          // Filtered before the four-tab cap so a disabled tab never costs an available one its
          // slot.
          val tabs = browserManager.queryTabs().filterNot { it.disabled == true }
          if (tabs.size > 4) {
            Timber.w(
              "Root has ${tabs.size} tabs; dropping ${tabs.size - 4} (Android Auto root limit)"
            )
          }
          toFlatMediaItems(tabs.take(4))
        } else {
          val resolvedTrack = browserManager.resolve(parentId)
          // Section styles arrive page-folded (ADR 0011).
          val sections =
            resolvedTrack.styleResolvedSections()
              ?: throw IllegalStateException("Expected browsed ResolvedTrack to have sections")
          browserManager.warnIfGridPageLacksPromise(parentId, sections)
          // An empty page resolved fine, so no SessionError: these paths are re-queried by
          // notifyChildrenChanged without user action.
          browseLevelItems(toMediaItems(sections)) {
            audioBrowser.resolveFormattedError(emptyContentError(), parentId)
          }
        }

      LibraryResult.ofItemList(ImmutableList.copyOf(children.paginate(page, pageSize)), params)
    } catch (e: Exception) {
      // A cancelled browse (the controller left) is not a failure; awaitBrowser's timeout is.
      if (e is CancellationException && e !is TimeoutCancellationException) throw e
      Timber.e(e, "Error getting children for parentId: $parentId")
      // A tile, not ofError(): see createErrorMediaItem and
      // https://github.com/androidx/media/issues/2901
      sendBrowseError(session, browser, offline = false)
      LibraryResult.ofItemList(
        ImmutableList.of(
          createBrowseErrorMediaItem(
            audioBrowser.resolveFormattedError(navigationErrorFor(e), parentId)
          )
        ),
        params,
      )
    }
  }

  /**
   * Sends [browser] a non-fatal [SessionError] alongside the error tile. Current Android Auto
   * renders nothing for it (https://github.com/androidx/media/issues/2901); it is the correct
   * Media3 signal and shows if Android Auto starts reading it. Dispatched to Main: the session and
   * its legacy stub are application-thread bound.
   */
  @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
  private fun sendBrowseError(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    offline: Boolean,
  ) {
    val message =
      player.context.getString(
        if (offline) com.audiobrowser.R.string.audio_browser_offline_error
        else com.audiobrowser.R.string.audio_browser_browse_error
      )
    val code = if (offline) SessionError.ERROR_IO else SessionError.ERROR_UNKNOWN
    scope.launch(Dispatchers.Main) { session.sendError(browser, SessionError(code, message)) }
  }

  /**
   * Flattens sections to browse items (ADR 0010): the MediaBrowser protocol has no section node, so
   * each item carries its section's title and style hints. A section with a `path` gains a "view
   * more" row at every `display`: no Android Auto header is tappable, so it is the only way to the
   * whole section.
   */
  private suspend fun toMediaItems(sections: List<Section>): List<MediaItem> {
    val registry = player.browseArtworkRegistry
    val authority = com.audiobrowser.util.ArtworkUris.authorityFor(player.context.packageName)
    return sections.flatMap { section ->
      // Android Auto has no disabled affordance, so a disabled track hides.
      val children = section.children.filter { it.disabled != true }
      val items = children.map { TrackFactory.toBrowseMediaItem(it, registry, authority, section) }
      // A lone "view more" row under a header is a dead end.
      if (section.path == null || children.isEmpty()) return@flatMap items
      // Free after the first row: the browser is already up, and viewMoreTitle is memoized per
      // content generation.
      val title = player.awaitBrowser().browserManager.viewMoreTitle()
      items +
        TrackFactory.toBrowseMediaItem(
          TrackFactory.navigationTrack(section, title),
          registry,
          authority,
          section,
        )
    }
  }

  // One untitled section, so a single Track→MediaItem conversion exists.
  private suspend fun toFlatMediaItems(tracks: List<Track>): List<MediaItem> =
    toMediaItems(listOf(untitledSection(tracks.toTypedArray())))

  override fun onGetItem(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    mediaId: String,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    Timber.Forest.d("onGetItem: ${browser.packageName}, mediaId = $mediaId")
    return scope.future {
      if (mediaId == BrowserPathHelper.ROOT_PATH) {
        return@future LibraryResult.ofItem(
          MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
              MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build()
            )
            .build(),
          null,
        )
      }

      if (mediaId == BrowserPathHelper.OFFLINE_PATH) {
        return@future LibraryResult.ofItem(
          createOfflineMediaItem(
            player
              .awaitBrowser()
              .resolveFormattedError(offlineError(), BrowserPathHelper.OFFLINE_PATH)
          ),
          null,
        )
      }

      if (mediaId == BrowserPathHelper.ERROR_PATH) {
        return@future LibraryResult.ofItem(
          createBrowseErrorMediaItem(
            player
              .awaitBrowser()
              .resolveFormattedError(browseFailureError(), BrowserPathHelper.ERROR_PATH)
          ),
          null,
        )
      }

      if (mediaId == BrowserPathHelper.EMPTY_PATH) {
        return@future LibraryResult.ofItem(
          createEmptyMediaItem(
            player
              .awaitBrowser()
              .resolveFormattedError(emptyContentError(), BrowserPathHelper.EMPTY_PATH)
          ),
          null,
        )
      }

      // No onGate: it fires where a browse or search is served, not on an item lookup.
      if (mediaId == BrowserPathHelper.GATE_PATH) {
        val outcome =
          player
            .awaitBrowser()
            .gateDecision(
              NativeGateRequest(
                reason = GateReason.BROWSE,
                path = BrowserPathHelper.GATE_PATH,
                search = null,
              )
            )
        if (!outcome.gated) return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        return@future LibraryResult.ofItem(createGateMediaItem(outcome.chrome!!), null)
      }

      val browserManager = player.awaitBrowser().browserManager

      // Cache first: resolve() strips __trackId from a contextual mediaId and would answer with the
      // parent container's metadata.
      val browseAuthority =
        com.audiobrowser.util.ArtworkUris.authorityFor(player.context.packageName)
      browserManager.getCachedTrack(mediaId)?.let { track ->
        return@future LibraryResult.ofItem(
          TrackFactory.toBrowseMediaItem(track, player.browseArtworkRegistry, browseAuthority),
          null,
        )
      }

      try {
        val resolvedTrack = browserManager.resolve(mediaId)
        // The same conversion as the cached path, so the item's tag is a Track, as fromMedia3
        // expects.
        LibraryResult.ofItem(
          TrackFactory.toBrowseMediaItem(
            resolvedTrack.toTrack(),
            player.browseArtworkRegistry,
            browseAuthority,
          ),
          null,
        )
      } catch (e: Exception) {
        if (e is CancellationException && e !is TimeoutCancellationException) throw e
        Timber.e(e, "Error getting item for mediaId: $mediaId")
        LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
      }
    }
  }

  override fun onSubscribe(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    parentId: String,
    params: MediaLibraryService.LibraryParams?,
  ): ListenableFuture<LibraryResult<Void>> {
    Timber.d("onSubscribe: ${browser.packageName}, parentId = $parentId")

    synchronized(this) { parentIdSubscriptions.getOrPut(parentId) { mutableSetOf() }.add(browser) }

    // Not super: its onSubscribe validates the parent through onGetItem, a full resolve whose
    // result the legacy stub discards. Kept from it: Media3 browsers are told to fetch; legacy
    // browsers get onLoadChildren from the framework.
    if (browser.controllerVersion != MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) {
      session.notifyChildrenChanged(browser, parentId, Int.MAX_VALUE, params)
    }
    return Futures.immediateFuture(LibraryResult.ofVoid())
  }

  override fun onUnsubscribe(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    parentId: String,
  ): ListenableFuture<LibraryResult<Void>> {
    Timber.d("onUnsubscribe: ${browser.packageName}, parentId = $parentId")

    synchronized(this) {
      parentIdSubscriptions[parentId]?.remove(browser)
      if (parentIdSubscriptions[parentId]?.isEmpty() == true) {
        parentIdSubscriptions.remove(parentId)
      }
    }

    return super.onUnsubscribe(session, browser, parentId)
  }

  /** Safe from any thread. */
  private fun notifySubscribedChildrenChanged() {
    val session = mediaLibrarySession ?: return
    val parentIds = synchronized(this) { parentIdSubscriptions.keys.toList() }
    scope.launch(Dispatchers.Main) {
      parentIds.forEach { parentId -> session.notifyChildrenChanged(parentId, Int.MAX_VALUE, null) }
    }
  }

  /** Tells controllers subscribed to [path] to re-query it. Safe from any thread. */
  fun notifyContentChanged(path: String) {
    Timber.d("Notifying content changed for path: $path")
    val session = mediaLibrarySession ?: return
    scope.launch(Dispatchers.Main) { session.notifyChildrenChanged(path, Int.MAX_VALUE, null) }
  }

  /** Pairs with AudioBrowser.invalidateAllContent(), which clears the content cache first. */
  fun invalidateAllContent() {
    Timber.d("Invalidating all content - notifying all subscribed paths")
    notifySubscribedChildrenChanged()
  }

  fun notifyBrowserReady() {
    Timber.d("Browser ready - notifying all subscribed paths")
    notifySubscribedChildrenChanged()
  }

  fun destroy() {
    scope.cancel()
  }

  override fun onSearch(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    params: MediaLibraryService.LibraryParams?,
  ): ListenableFuture<LibraryResult<Void>> {
    Timber.d("onSearch: ${browser.packageName}, query = $query")
    return scope.future {
      val audioBrowser =
        try {
          player.awaitBrowser()
        } catch (e: TimeoutCancellationException) {
          Timber.w("Timed out waiting for browser - search not available")
          return@future LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
        }
      val browserManager = audioBrowser.browserManager

      val searchOutcome =
        audioBrowser.gateDecision(
          NativeGateRequest(reason = GateReason.SEARCH, path = null, search = searchParams(query))
        )
      // Search is gated with the browse tree, or it would route around the gate. One result: the
      // gate tile (see onGetSearchResult).
      if (searchOutcome.gated) {
        audioBrowser.onGate(GateEvent(GateReason.SEARCH))
        session.notifySearchResultChanged(browser, query, 1, params)
        return@future LibraryResult.ofVoid()
      }

      // Before announceSearch's offline branch: no search source is not-supported, online or not.
      if (!browserManager.config.hasSearch) {
        Timber.w("Search requested but no search source configured")
        return@future LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
      }

      announceSearch(session, browser, browserManager, query, params)
      LibraryResult.ofVoid()
    }
  }

  /**
   * Tells [browser] how many items [onGetSearchResult] will serve for [query]: its hits, or the one
   * tile standing in for them, since a controller told zero never asks. Offline keeps the query for
   * [reannounceOfflineSearches]; any other outcome drops it.
   */
  private suspend fun announceSearch(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    browserManager: BrowserManager,
    query: String,
    params: MediaLibraryService.LibraryParams?,
  ) {
    if (
      !player.networkMonitor.isOnline.value && browserManager.config.androidControllerOfflineError
    ) {
      Timber.w("Network offline - announcing the offline tile for query: $query")
      offlineSearches.record(browser, query, params)
      session.notifySearchResultChanged(browser, query, 1, params)
      return
    }
    offlineSearches.forget(browser)

    try {
      // Caches the results under the query, for getCachedSearchResults.
      val searchResults = browserManager.search(query)
      failedSearch.clear()
      val resultCount = announcedSearchCount(searchResults.normalizedSections)

      Timber.d("Search announced $resultCount item(s) for query '$query'")

      session.notifySearchResultChanged(browser, query, resultCount, params)
    } catch (e: Exception) {
      if (e is CancellationException && e !is TimeoutCancellationException) throw e
      Timber.e(e, "Error during search for query: $query")
      // Announces the browse-error tile: an ofError renders as nothing, and without the slot the
      // failure reads as "no results" by the time onGetSearchResult arrives.
      failedSearch.record(query, navigationErrorFor(e))
      session.notifySearchResultChanged(browser, query, 1, params)
    }
  }

  /**
   * Re-runs each search served the offline tile, so the announced count matches what
   * [onGetSearchResult] then serves from the cache this fills.
   */
  private suspend fun reannounceOfflineSearches() {
    val pending = offlineSearches.drain()
    if (pending.isEmpty()) return
    val session = mediaLibrarySession ?: return
    val browserManager =
      try {
        player.awaitBrowser().browserManager
      } catch (e: TimeoutCancellationException) {
        Timber.w("Timed out waiting for browser - offline searches not re-announced")
        return
      }

    pending.forEach { search ->
      Timber.d("Network back - re-running search: ${search.query}")
      announceSearch(session, search.browser, browserManager, search.query, search.params)
    }
  }

  override fun onGetSearchResult(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    page: Int,
    pageSize: Int,
    params: MediaLibraryService.LibraryParams?,
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    Timber.d(
      "onGetSearchResult: ${browser.packageName}, query = $query, page = $page, pageSize = $pageSize"
    )
    return scope.future {
      val audioBrowser =
        try {
          player.awaitBrowser()
        } catch (e: TimeoutCancellationException) {
          Timber.w("Timed out waiting for browser - search not available")
          return@future LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
        }
      val browserManager = audioBrowser.browserManager

      val searchOutcome =
        audioBrowser.gateDecision(
          NativeGateRequest(reason = GateReason.SEARCH, path = null, search = searchParams(query))
        )
      // The one result onSearch announced for a gated search.
      if (searchOutcome.gated) {
        audioBrowser.onGate(GateEvent(GateReason.SEARCH))
        return@future LibraryResult.ofItemList(
          ImmutableList.of(createGateMediaItem(searchOutcome.chrome!!)),
          params,
        )
      }

      // Before the failure slot, as in onGetChildren: a search that never reached the network is
      // fixed by connectivity, not a server failure.
      if (
        !player.networkMonitor.isOnline.value && browserManager.config.androidControllerOfflineError
      ) {
        Timber.w("Network offline - returning the offline tile for query: $query")
        sendBrowseError(session, browser, offline = true)
        return@future LibraryResult.ofItemList(
          ImmutableList.of(
            createOfflineMediaItem(
              audioBrowser.resolveFormattedError(
                offlineError(),
                BrowserPathHelper.createSearchPath(query),
              )
            )
          ),
          params,
        )
      }

      // The search failed: the same tile and SessionError as a failed browse, not the empty page.
      failedSearch.errorFor(query)?.let { error ->
        sendBrowseError(session, browser, offline = false)
        return@future LibraryResult.ofItemList(
          ImmutableList.of(
            createBrowseErrorMediaItem(
              audioBrowser.resolveFormattedError(error, BrowserPathHelper.createSearchPath(query))
            )
          ),
          params,
        )
      }

      try {
        val tracks = browserManager.getCachedSearchResults(query)
        if (tracks == null) Timber.w("No cached search results for query: $query")
        // No hits, all disabled, or nothing cached: the app's empty copy (ADR 0001). Something must
        // come back: onSearch announced at least one item.
        val mediaItems =
          browseLevelItems(toFlatMediaItems(tracks?.toList().orEmpty())) {
            audioBrowser.resolveFormattedError(
              emptyContentError(),
              BrowserPathHelper.createSearchPath(query),
            )
          }

        val paginatedItems = mediaItems.paginate(page, pageSize)
        Timber.d("Returning ${paginatedItems.size} search results")
        LibraryResult.ofItemList(ImmutableList.copyOf(paginatedItems), params)
      } catch (e: Exception) {
        if (e is CancellationException && e !is TimeoutCancellationException) throw e
        Timber.e(e, "Error getting search results for query: $query")
        LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
      }
    }
  }

  override fun onSetMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: MutableList<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    Timber.Forest.d(
      "onSetMediaItems: ${controller.packageName}, count=${mediaItems.size}, mediaId=${mediaItems.firstOrNull()?.mediaId}, uri=${mediaItems.firstOrNull()?.localConfiguration?.uri}, title=${mediaItems.firstOrNull()?.mediaMetadata?.title}"
    )

    if (mediaItems.isEmpty()) {
      return Futures.immediateFuture(
        MediaSession.MediaItemsWithStartPosition(emptyList(), 0, startPositionMs)
      )
    }

    return scope.future {
      val audioBrowser = player.awaitBrowser()

      // Media3 sets whatever this returns: the controller's position would undo the handler's seek.
      // Reads hop to Main: ExoPlayer is main-thread confined and this runs on IO.
      suspend fun currentPlayerState(): MediaSession.MediaItemsWithStartPosition =
        withContext(Dispatchers.Main) {
          val currentItems = player.tracks.map { TrackFactory.toMedia3(it) }
          val currentIndex = player.currentIndex ?: 0
          MediaSession.MediaItemsWithStartPosition(
            currentItems,
            currentIndex,
            player.restorablePositionMs,
          )
        }

      val browserManager = audioBrowser.browserManager

      // A single tapped item resolves to the contextual path of the list it was tapped in. A search
      // pick is not a list tap: its queue comes from the search results.
      val singleContextualPath =
        mediaItems
          .singleOrNull()
          ?.takeIf { it.requestMetadata.searchQuery == null }
          ?.let { browserManager.contextualPathFor(it.mediaId) }

      if (singleContextualPath != null) {
        val parentPath = BrowserPathHelper.stripTrackId(singleContextualPath)
        val trackId = BrowserPathHelper.extractTrackId(singleContextualPath)

        // A tap within the queue's own source list skips rather than rebuilds.
        if (trackId != null && parentPath == player.queueSourcePath) {
          val queueTracks = withContext(Dispatchers.Main) { player.tracks }
          val index = queueTracks.indexOfTappedTrack(singleContextualPath, trackId)
          if (index >= 0) {
            Timber.d("Queue already from $parentPath, skipping to index $index")
            val track = queueTracks[index]
            return@future handleTrackLoad(
              audioBrowser.configuration.handleTrackLoad,
              track,
              queueTracks,
              index.toDouble(),
              ::currentPlayerState,
            ) {
              val existingItems = queueTracks.map { TrackFactory.toMedia3(it) }
              MediaSession.MediaItemsWithStartPosition(existingItems, index, startPositionMs)
            }
          }
        }
      }

      val result =
        browserManager.resolveMediaItemsForPlayback(mediaItems, startIndex, startPositionMs)

      val tracks = result.mediaItems.map { TrackFactory.fromMedia3(it) }.toTypedArray()
      val selectedTrack = tracks.getOrElse(result.startIndex) { tracks.first() }

      handleTrackLoad(
        audioBrowser.configuration.handleTrackLoad,
        selectedTrack,
        tracks,
        result.startIndex.toDouble(),
        ::currentPlayerState,
      ) {
        // Only on handleTrackLoad's default branch: a consumer that handles the load owns the
        // queue.
        if (singleContextualPath != null) {
          val parentPath = BrowserPathHelper.stripTrackId(singleContextualPath)
          withContext(Dispatchers.Main) { player.queueSourcePath = parentPath }
        }
        result
      }
    }
  }

  /**
   * Rebuilds the persisted queue for a system request (Bluetooth play, a head unit). With
   * [isForPlayback] false it is the boot-time resumption notification asking: no network, stored
   * track only.
   *
   * @see https://developer.android.com/media/media3/session/background-playback#resumption
   */
  override fun onPlaybackResumption(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    isForPlayback: Boolean,
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    val resumption = resume(controller, isForPlayback)
    if (isForPlayback) {
      Futures.addCallback(
        resumption,
        object : FutureCallback<MediaSession.MediaItemsWithStartPosition> {
          override fun onSuccess(result: MediaSession.MediaItemsWithStartPosition) {}

          override fun onFailure(t: Throwable) = player.onPlaybackResumptionFailed()
        },
        ContextCompat.getMainExecutor(player.context),
      )
    }
    return resumption
  }

  private fun resume(
    controller: MediaSession.ControllerInfo,
    isForPlayback: Boolean,
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    Timber.d("${controller.packageName}")

    return scope.future {
      // A pure read: restore() applies repeat/shuffle/speed to the live player and emits events to
      // JS, so it waits for actual resumption.
      val state =
        player.playbackStateStore.get()
          ?: run {
            Timber.w("No persisted playback state found")
            throw IllegalStateException("No playback state to resume")
          }

      // A track disabled since it was saved never plays; failing the future refuses it without
      // touching the player.
      if (state.track.disabled == true) {
        Timber.w("Refusing resumption of disabled track: ${state.track.title}")
        throw IllegalStateException("Persisted track is disabled")
      }

      // Before the restore and the expansion: both touch the live player or the network.
      if (!isForPlayback) {
        Timber.d("Info-gathering resumption (boot-time); returning stored track without expansion")
        return@future MediaSession.MediaItemsWithStartPosition(
          ImmutableList.of(TrackFactory.toMedia3(state.track)),
          0,
          state.positionMs,
        )
      }

      withContext(Dispatchers.Main) { player.playbackStateStore.restore() }

      val path = state.track.path
      Timber.d("Resuming from path=$path, positionMs=${state.positionMs}")

      val browserManager = player.awaitBrowser().browserManager

      val expanded = path?.let { browserManager.expandQueueFromContextualPath(it) }

      if (expanded != null) {
        val (tracks, selectedIndex) = expanded
        Timber.d(
          "Restored ${tracks.size} tracks, starting at index $selectedIndex at ${state.positionMs}ms"
        )

        if (BrowserPathHelper.isContextual(path)) {
          val parentPath = BrowserPathHelper.stripTrackId(path)
          withContext(Dispatchers.Main) { player.queueSourcePath = parentPath }
        }

        MediaSession.MediaItemsWithStartPosition(
          ImmutableList.copyOf(TrackFactory.toMedia3(tracks)),
          selectedIndex,
          state.positionMs,
        )
      } else {
        Timber.d("Queue expansion failed, using stored track: ${state.track.title}")
        MediaSession.MediaItemsWithStartPosition(
          ImmutableList.of(TrackFactory.toMedia3(state.track)),
          0,
          state.positionMs,
        )
      }
    }
  }
}

/**
 * What a drill-in on a tile's sentinel path serves. Nothing, so Android Auto, which drills into a
 * tile whatever its flags, dead-ends at "No items" rather than stacking error pages. Android
 * Automotive OS shows a root serving only a tile as one tab that drills in here, and an empty level
 * as "Media isn't available for this list", so there [rootTile] is served again, unless it is the
 * offline tile and the device is back online.
 */
internal fun deadEndChildren(
  parentId: String,
  rootTile: MediaItem?,
  automotive: Boolean,
  offline: Boolean,
): List<MediaItem> =
  listOfNotNull(
    rootTile?.takeIf {
      automotive &&
        it.mediaId == parentId &&
        (parentId != BrowserPathHelper.OFFLINE_PATH || offline)
    }
  )

/** The single tile of a level that serves one in place of its items, or null. */
internal fun deadEndTileOf(items: List<MediaItem>): MediaItem? =
  items.singleOrNull()?.takeIf { BrowserPathHelper.isDeadEndPath(it.mediaId) }

/**
 * A non-playable item that stands in for a browse list's items: the only in-browse error signal.
 * Media3 drops [LibraryResult.ofError] on the legacy browse bridge, leaving "No items", and Android
 * Auto's browse list shows no text for a transient or replicated [SessionError]. Fatal replication
 * shows it on the playback screen only by putting the session in STATE_ERROR, which hides
 * now-playing and transport. https://github.com/androidx/media/issues/2901
 */
internal fun createErrorMediaItem(mediaId: String, title: String, subtitle: String): MediaItem =
  MediaItem.Builder()
    .setMediaId(mediaId)
    .setMediaMetadata(
      MediaMetadata.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        // Android Automotive OS drops an item that is neither browsable nor playable; Android Auto
        // ignores these flags and drills in anyway (see deadEndChildren).
        .setIsBrowsable(Automotive.device)
        .setIsPlayable(false)
        .build()
    )
    .build()

private fun createErrorMediaItem(mediaId: String, formatted: FormattedNavigationError): MediaItem =
  createErrorMediaItem(
    mediaId = mediaId,
    title = formatted.title,
    // List rows are single-line.
    subtitle = formatted.message?.replace('\n', ' ') ?: "",
  )

/**
 * From the app's path-aware empty copy (ADR 0001). No SessionError goes with it: an empty page
 * resolved fine.
 */
internal fun createEmptyMediaItem(formatted: FormattedNavigationError): MediaItem =
  createErrorMediaItem(BrowserPathHelper.EMPTY_PATH, formatted)

/** From the app's copy, so one browse list never mixes the app's locale with the device's. */
internal fun createOfflineMediaItem(formatted: FormattedNavigationError): MediaItem =
  createErrorMediaItem(BrowserPathHelper.OFFLINE_PATH, formatted)

/**
 * For a request that failed while online; offline is caught earlier by the networkMonitor guards,
 * so this must not say "no internet connection".
 */
internal fun createBrowseErrorMediaItem(formatted: FormattedNavigationError): MediaItem =
  createErrorMediaItem(BrowserPathHelper.ERROR_PATH, formatted)

/**
 * A non-root level's or a search's items, or the lone empty tile. [formatted] is a JS hop, run only
 * when there are none. The root is not served through here.
 */
internal suspend fun browseLevelItems(
  children: List<MediaItem>,
  formatted: suspend () -> FormattedNavigationError,
): List<MediaItem> = children.ifEmpty { listOf(createEmptyMediaItem(formatted())) }

/** No message of its own: the path carries the copy. */
internal fun emptyContentError(): NavigationError =
  NavigationError(NavigationErrorType.EMPTY_CONTENT, "", null, null)

internal fun offlineError(): NavigationError =
  NavigationError(NavigationErrorType.NETWORK_ERROR, "", null, null)

/** What a re-read of the browse-error tile formats under: its exception is gone. */
internal fun browseFailureError(): NavigationError =
  NavigationError(NavigationErrorType.UNKNOWN_ERROR, "", null, null)

/**
 * Disabled tracks hide, so they do not count. Never zero: a controller told zero never asks for
 * items, and the empty tile [MediaSessionCallback.onGetSearchResult] serves stands in.
 */
internal fun announcedSearchCount(sections: List<Section>?): Int =
  (sections?.sumOf { section -> section.children.count { it.disabled != true } } ?: 0)
    .coerceAtLeast(1)

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
