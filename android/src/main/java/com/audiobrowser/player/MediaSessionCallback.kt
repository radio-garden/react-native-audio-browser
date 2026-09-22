package com.audiobrowser.player

import android.os.Bundle
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
import com.audiobrowser.browser.handleTrackLoad
import com.audiobrowser.browser.normalizedSections
import com.audiobrowser.browser.styleResolvedSections
import com.audiobrowser.browser.untitledSection
import com.audiobrowser.extension.indexOfTappedTrack
import com.audiobrowser.extension.toTrack
import com.audiobrowser.navigationErrorFor
import com.audiobrowser.util.BrowserPathHelper
import com.audiobrowser.util.RatingFavorites
import com.audiobrowser.util.TrackFactory
import com.google.common.collect.ImmutableList
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
 * Everything the MediaLibrarySession serves except transport: browse, search, item lookup, queue
 * building, playback resumption, and the favorite and rating commands. Transport does not arrive
 * here — it reaches the player through [InterceptingPlayer], and Assistant's play-from-search
 * arrives as an intent handled in the service. The callbacks that answer through [scope] run on IO,
 * so anything touching the ExoPlayer instance hops back to [Dispatchers.Main]; the rest answer
 * synchronously on the Media3 application thread.
 */
class MediaSessionCallback(private val player: Player) :
  MediaLibraryService.MediaLibrarySession.Callback {
  internal val commandManager = MediaSessionCommandManager()
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  // Track which controllers are subscribed to which media IDs.
  // Mutated on the Media3 application thread (onSubscribe/onUnsubscribe) but read from other
  // threads (network observer, JS-triggered notifies) — guard all access with synchronized(this).
  private val parentIdSubscriptions =
    mutableMapOf<String, MutableSet<MediaSession.ControllerInfo>>()
  private var mediaLibrarySession: MediaLibraryService.MediaLibrarySession? = null

  /**
   * Recommended artwork size in pixels from the connected media browser (e.g., Android Auto).
   * Updated when onGetLibraryRoot is called with EXTRAS_KEY_MEDIA_ART_SIZE_PIXELS hint.
   */
  @Volatile
  var artworkSizeHintPixels: Int? = null
    private set

  init {
    player.networkMonitor.observeOnline(scope) { _ -> notifySubscribedChildrenChanged() }
  }

  /** Media3's legacy browse bridge asks for a whole list with pageSize [Int.MAX_VALUE]. */
  private fun <T> List<T>.paginate(page: Int, pageSize: Int): List<T> {
    return if (pageSize in 1 until Int.MAX_VALUE) {
      this.drop(page * pageSize).take(pageSize)
    } else {
      this
    }
  }

  /**
   * Wraps a raw external-search query string into the structured [SearchParams] the gate request
   * carries. The car search surfaces only give a free-text query, so the other fields stay null —
   * mirrors BrowserManager's query→SearchParams construction.
   */
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

  /**
   * Builds the gate tile from a per-request chrome: while a gate is active, tabs stay visible but a
   * gated browse/search level serves this single tile (see the gate checks in [onGetChildren] /
   * [onGetSearchResult]). Same shape as the error tiles — non-browsable, non-playable — and
   * deliberately NOT accompanied by a SessionError: a gate is deliberate app state, not a failure.
   */
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

  override fun onCustomCommand(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
    command: SessionCommand,
    args: Bundle,
  ): ListenableFuture<SessionResult> {
    // A success here makes the controller flip its heart optimistically, so a no-op (no current
    // track) must report INVALID_STATE instead of success-then-revert.
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
    // A heart rating from a controller (e.g. Google Assistant "I like this") toggles the
    // now-playing favorite. setActiveTrackFavorited fires onFavoriteChanged so the consumer
    // persists it — the same path as the notification / CarPlay heart button. Report an honest
    // result (see onCustomCommand): INVALID_STATE when there is no current track to favorite.
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
      // Playback resumption is served by media3 itself: it intercepts System UI's EXTRA_RECENT
      // root request internally and answers through onPlaybackResumption, so that request never
      // reaches this callback. Any other browser asking for a recent root gets
      // ERROR_NOT_SUPPORTED — there is no browsable recents tree; recents are ordinary consumer
      // content (a tab or route).
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
      val audioBrowser =
        player.awaitBrowser().also { Timber.d("Browser ready, proceeding with onGetChildren") }
      val browserManager = audioBrowser.browserManager

      // While a gate is active, tabs stay visible (the root keeps serving them below) but each
      // non-root level is resolved per request: a gated path — including re-queries of the gate
      // tile's own sentinel path — serves the single gate tile, so drilling into it re-shows the
      // message instead of dead-ending in "No items"; an allowed path falls through to real
      // children. Checked before the offline guard: gated content isn't coming back with
      // connectivity, so the gate copy is the truer message.
      if (parentId != BrowserPathHelper.ROOT_PATH) {
        val outcome =
          audioBrowser.gateDecision(
            NativeGateRequest(reason = GateReason.BROWSE, path = parentId, search = null)
          )
        if (outcome.gated) {
          audioBrowser.onGate(GateEvent(GateReason.BROWSE))
          // gateDecision guarantees non-null chrome on a gated decision (override → default →
          // built-in).
          return@future LibraryResult.ofItemList(
            ImmutableList.of(createGateMediaItem(outcome.chrome!!)),
            params,
          )
        }
      }

      // The error / offline tiles are dead-ends. Some controllers (e.g. Android Auto when online)
      // treat a non-browsable tile as tappable and subscribe to its mediaId anyway; returning the
      // error tile again here would push an endless stack of error pages. Return nothing instead.
      // For the offline tile, re-send the alert when still offline so a tap surfaces the
      // explanation again — but only then, since these paths are also re-queried by
      // notifyChildrenChanged (e.g. when connectivity returns) without any user action.
      if (parentId == BrowserPathHelper.OFFLINE_PATH || parentId == BrowserPathHelper.ERROR_PATH) {
        if (parentId == BrowserPathHelper.OFFLINE_PATH && !player.networkMonitor.isOnline.value) {
          sendBrowseError(session, browser, offline = true)
        }
        return@future LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), params)
      }

      if (
        !player.networkMonitor.isOnline.value && browserManager.config.androidControllerOfflineError
      ) {
        Timber.w("Network offline - returning error message for: $parentId")
        sendBrowseError(session, browser, offline = true)
        return@future LibraryResult.ofItemList(
          ImmutableList.of(
            createOfflineMediaItem(audioBrowser.resolveFormattedError(offlineError(), parentId))
          ),
          params,
        )
      }

      try {
        val children =
          if (parentId == BrowserPathHelper.ROOT_PATH) {
            // Disabled tabs hide (Track.disabled) — filtered before the four-tab cap so an
            // unavailable tab never costs an available one its slot.
            val tabs = browserManager.queryTabs().filterNot { it.disabled == true }
            if (tabs.size > 4) {
              Timber.w(
                "Root has ${tabs.size} tabs; dropping ${tabs.size - 4} (Android Auto root limit)"
              )
            }
            toFlatMediaItems(tabs.take(4))
          } else {
            // Section styles arrive page-folded (`section ?? page`, ADR 0011).
            val resolvedTrack = browserManager.resolve(parentId)
            val sections =
              resolvedTrack.styleResolvedSections()
                ?: throw IllegalStateException("Expected browsed ResolvedTrack to have sections")
            browserManager.warnIfGridPageLacksPromise(parentId, sections)
            toMediaItems(sections)
          }

        LibraryResult.ofItemList(ImmutableList.copyOf(children.paginate(page, pageSize)), params)
      } catch (e: Exception) {
        // A cancelled browse (controller disconnected) is not a failure — don't render it as an
        // error tile. awaitBrowser's TimeoutCancellationException IS a real failure, keep that.
        if (e is CancellationException && e !is TimeoutCancellationException) throw e
        Timber.e(e, "Error getting children for parentId: $parentId")
        // A tile rather than a bare ofError() — see createErrorMediaItem and
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
  }

  /**
   * Sends a non-fatal [SessionError] to the controller whose browse failed, complementing the error
   * tile. Media3 browsers receive it via MediaController.Listener.onError; for legacy controllers
   * Media3 transiently attaches the error code/message to the platform session's playback state
   * without entering STATE_ERROR.
   *
   * NOTE: current Android Auto renders nothing for this — its transient state is cleared
   * microseconds after being set (see [createErrorMediaItem], androidx/media#2901). It is kept
   * because it is the correct Media3-API error signal, costs nothing, and becomes user-visible if
   * Android Auto moves to consuming SessionError via the Media3 controller API.
   *
   * Dispatched to the main thread: the session and its legacy stub are application-thread bound.
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
   * Flattens a page's sections to MediaItems for browse delivery — the Media3 boundary where
   * sections die (ADR 0010): the MediaBrowser protocol has no section node, so each child is
   * stamped with its owning section's title/style hints. A section with a `path` gains a browsable
   * "view more" row under the same header — at every `display`, because no Android Auto header is
   * tappable at any of them, so the row is the section's only "view all" affordance here. Http(s)
   * artwork routes through the content:// provider so Android Auto can load it via the
   * ArtworkContentProvider.
   */
  private suspend fun toMediaItems(sections: List<Section>): List<MediaItem> {
    val registry = player.browseArtworkRegistry
    val authority = com.audiobrowser.util.ArtworkUris.authorityFor(player.context.packageName)
    return sections.flatMap { section ->
      // Android Auto has no disabled affordance, so an unavailable track hides
      // — never a normal-looking dead row (Track.disabled's rendering ladder).
      val children = section.children.filter { it.disabled != true }
      val items = children.map { TrackFactory.toBrowseMediaItem(it, registry, authority, section) }
      // No "view more" under an empty section — CarPlay skips empty sections
      // outright, and a lone navigation tile under a header is a dead end.
      if (section.path == null || children.isEmpty()) return@flatMap items
      // The browser is already up at every call site, so awaiting it here is a field read, and
      // viewMoreTitle is memoized per content generation — both are free after the first row.
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

  /**
   * Flat lists (tabs, search results) convert as one untitled list section — which contributes no
   * group or style hints — so exactly one Track→MediaItem path exists at the Media3 boundary.
   */
  private suspend fun toFlatMediaItems(tracks: List<Track>): List<MediaItem> =
    toMediaItems(listOf(untitledSection(tracks.toTypedArray())))

  override fun onGetItem(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    mediaId: String,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    Timber.Forest.d("onGetItem: ${browser.packageName}, mediaId = $mediaId")
    return scope.future {
      // Only the root answers ahead of awaitBrowser: it needs no browser. Every tile sentinel
      // below does — for the formatter hop, and the gate one for gateDecision.
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
        // The exception that produced the tile is gone by now — the sentinel is all the controller
        // hands back — so the copy formats for the sentinel under the generic failure code.
        return@future LibraryResult.ofItem(
          createBrowseErrorMediaItem(
            player
              .awaitBrowser()
              .resolveFormattedError(browseFailureError(), BrowserPathHelper.ERROR_PATH)
          ),
          null,
        )
      }

      if (mediaId == BrowserPathHelper.GATE_PATH) {
        // A direct fetch of the gate tile's own sentinel (a controller re-reading the item it was
        // served). Resolve the chrome for the gate path but do NOT emit onGate — that fires at the
        // browse/search serve sites, not on an item lookup.
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

      // Serve tracks from the track cache first (keyed by path and src). Besides avoiding an HTTP
      // resolve, this keeps item identity correct for contextual mediaIds: resolve() strips
      // __trackId and would return the *parent container's* metadata as the item.
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
        // Through the one Track conversion, so the resolve path renders identically
        // to the cached-track path above (list line from subtitle, favorited heart)
        // and the item's tag is a Track, as fromMedia3 expects.
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

    // Don't call super: Media3's default onSubscribe validates the parent by calling
    // onGetItem(parentId), which for us is a full resolve() — an HTTP fetch whose result the
    // legacy stub discards for Android Auto controllers. onGetChildren already surfaces browse
    // failures. Keep the default's other behavior: notify Media3 (non-legacy) browsers so they
    // fetch the children; legacy browsers get onLoadChildren from the framework after subscribing.
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

  /**
   * Safe to call from any thread: snapshots the subscribed paths and dispatches the session calls
   * to the main (Media3 application) thread.
   */
  private fun notifySubscribedChildrenChanged() {
    val session = mediaLibrarySession ?: return
    val parentIds = synchronized(this) { parentIdSubscriptions.keys.toList() }
    scope.launch(Dispatchers.Main) {
      parentIds.forEach { parentId -> session.notifyChildrenChanged(parentId, Int.MAX_VALUE, null) }
    }
  }

  /**
   * Notifies external controllers that content at the given path has changed, so controllers
   * subscribed to it refresh their UI. Safe to call from any thread.
   */
  fun notifyContentChanged(path: String) {
    Timber.d("Notifying content changed for path: $path")
    val session = mediaLibrarySession ?: return
    scope.launch(Dispatchers.Main) { session.notifyChildrenChanged(path, Int.MAX_VALUE, null) }
  }

  /**
   * Notifies all subscribed controllers that content everywhere has changed (e.g. on a locale
   * switch) so they re-query their children. Pairs with AudioBrowser.invalidateAllContent(), which
   * clears the content cache first.
   */
  fun invalidateAllContent() {
    Timber.d("Invalidating all content - notifying all subscribed paths")
    notifySubscribedChildrenChanged()
  }

  fun notifyBrowserReady() {
    Timber.d("Browser ready - notifying all subscribed paths")
    notifySubscribedChildrenChanged()
  }

  /** Cancels in-flight browse/search work. Call when the owning player is destroyed. */
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

      // External-surface search is gated with the rest of the browse tree — otherwise search is
      // a way around the gate. One "result": the gate tile (see onGetSearchResult).
      val searchOutcome =
        audioBrowser.gateDecision(
          NativeGateRequest(reason = GateReason.SEARCH, path = null, search = searchParams(query))
        )
      if (searchOutcome.gated) {
        audioBrowser.onGate(GateEvent(GateReason.SEARCH))
        session.notifySearchResultChanged(browser, query, 1, params)
        return@future LibraryResult.ofVoid()
      }

      if (!browserManager.config.hasSearch) {
        Timber.w("Search requested but no search source configured")
        return@future LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
      }

      try {
        // Caches the result set under the query, which onGetSearchResult reads back through
        // getCachedSearchResults.
        val searchResults = browserManager.search(query)
        // Count what onGetSearchResult will actually serve: disabled tracks
        // hide on Android Auto, so they must not inflate the announced count.
        val resultCount =
          searchResults.normalizedSections?.sumOf { section ->
            section.children.count { it.disabled != true }
          } ?: 0

        Timber.d("Search completed: $resultCount results for query '$query'")

        session.notifySearchResultChanged(browser, query, resultCount, params)

        LibraryResult.ofVoid()
      } catch (e: Exception) {
        if (e is CancellationException && e !is TimeoutCancellationException) throw e
        Timber.e(e, "Error during search for query: $query")
        LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
      }
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

      // Gated: the single search "result" is the gate tile (paired with onSearch's count of 1).
      val searchOutcome =
        audioBrowser.gateDecision(
          NativeGateRequest(reason = GateReason.SEARCH, path = null, search = searchParams(query))
        )
      if (searchOutcome.gated) {
        audioBrowser.onGate(GateEvent(GateReason.SEARCH))
        return@future LibraryResult.ofItemList(
          ImmutableList.of(createGateMediaItem(searchOutcome.chrome!!)),
          params,
        )
      }

      try {
        browserManager.getCachedSearchResults(query)?.let { tracks ->
          val mediaItems = toFlatMediaItems(tracks.toList())

          val paginatedItems = mediaItems.paginate(page, pageSize)
          Timber.d("Returning ${paginatedItems.size} search results")
          LibraryResult.ofItemList(ImmutableList.copyOf(paginatedItems), params)
        }
          ?: run {
            Timber.w("No cached search results for query: $query")
            LibraryResult.ofItemList(ImmutableList.of(), params)
          }
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

      // The "leave playback alone" answer for handleTrackLoad: returning the current state
      // unchanged is how Media3 is told to do nothing. ExoPlayer is main-thread confined and this
      // future runs on IO, so the reads hop to Main.
      suspend fun currentPlayerState(): MediaSession.MediaItemsWithStartPosition =
        withContext(Dispatchers.Main) {
          val currentItems = player.tracks.map { TrackFactory.toMedia3(it) }
          val currentIndex = player.currentIndex ?: 0
          MediaSession.MediaItemsWithStartPosition(currentItems, currentIndex, startPositionMs)
        }

      val browserManager = audioBrowser.browserManager

      // A single tapped item resolves to the contextual path of the list it was
      // tapped in: directly for contextual mediaIds, via the track cache for
      // stable-id mediaIds (see BrowserManager.contextualPathFor). A search
      // selection is not a list tap — its queue comes from the search results
      // (resolveMediaItemsForPlayback), never from a browsed container.
      val singleContextualPath =
        mediaItems
          .singleOrNull()
          ?.takeIf { it.requestMetadata.searchQuery == null }
          ?.let { browserManager.contextualPathFor(it.mediaId) }

      if (singleContextualPath != null) {
        val parentPath = BrowserPathHelper.stripTrackId(singleContextualPath)
        val trackId = BrowserPathHelper.extractTrackId(singleContextualPath)

        // A tap inside the queue's own source list skips rather than rebuilds (exact path first,
        // identity for index-less paths — see indexOfTappedTrack).
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
        // Inside handleTrackLoad's default branch: a consumer that handles the load itself owns
        // the queue, so queueSourcePath must not claim this path on its behalf.
        if (singleContextualPath != null) {
          val parentPath = BrowserPathHelper.stripTrackId(singleContextualPath)
          withContext(Dispatchers.Main) { player.queueSourcePath = parentPath }
        }
        result
      }
    }
  }

  /**
   * Handles playback resumption requests from the system (Bluetooth play button, car head unit,
   * etc.).
   *
   * Reads the persisted playback state (URL + position + settings) and expands it into a full queue
   * using the browse callback. This enables seamless resumption after app restart with the same
   * player settings (repeat mode, shuffle, playback speed).
   *
   * @param isForPlayback True if this should start playback; false if just gathering info for the
   *   boot-time resumption notification (no network; local metadata only).
   * @see https://developer.android.com/media/media3/session/background-playback#resumption
   */
  override fun onPlaybackResumption(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    isForPlayback: Boolean,
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    Timber.d("${controller.packageName}")

    return scope.future {
      // A pure read first: restore()'s side effects (it applies repeat/shuffle/speed to the
      // live player, emitting option-changed events to JS) belong to actual resumption only.
      val state =
        player.playbackStateStore.get()
          ?: run {
            Timber.w("No persisted playback state found")
            throw IllegalStateException("No playback state to resume")
          }

      // The persisted track can carry `disabled` — it became unavailable after
      // it was saved. A disabled track never plays, resumption included
      // (Track.disabled); failing the future refuses without touching the
      // player.
      if (state.track.disabled == true) {
        Timber.w("Refusing resumption of disabled track: ${state.track.title}")
        throw IllegalStateException("Persisted track is disabled")
      }

      // Info-gathering returns before the settings restore and the queue expansion below: both
      // touch the live player or the network, and neither is permitted here.
      if (!isForPlayback) {
        Timber.d("Info-gathering resumption (boot-time); returning stored track without expansion")
        return@future MediaSession.MediaItemsWithStartPosition(
          ImmutableList.of(TrackFactory.toMedia3(state.track)),
          0,
          state.positionMs,
        )
      }

      // restore() sets player properties which must happen on main thread
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
 * Builds a non-browsable, non-playable [MediaItem] used to surface an error inside an Android Auto
 * / AAOS browse list. Rendered as a greyed-out, non-interactive tile, which is the only
 * side-effect-free way to communicate a browse failure to legacy controllers (Media3 drops
 * [LibraryResult.ofError] on the legacy browse bridge, leaving an empty "No items" screen).
 *
 * Tiles are the only in-browse error signal that exists. Verified on a head unit (2026-06): the
 * Android Auto browse list renders no error text for a transient [SessionError], for sticky
 * non-fatal replication, or for fatal replication (setLibraryErrorReplicationMode). Fatal
 * replication does surface the message on the playback screen, but at the cost of presenting the
 * session as STATE_ERROR with no actions, hiding the now-playing item and transport controls.
 */
internal fun createErrorMediaItem(mediaId: String, title: String, subtitle: String): MediaItem =
  MediaItem.Builder()
    .setMediaId(mediaId)
    .setMediaMetadata(
      MediaMetadata.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        // Android Auto ignores these flags and drills into a tapped tile anyway; onGetChildren
        // returns an empty list for the sentinel paths, so it dead-ends at "No Items" rather
        // than stacking error pages.
        .setIsBrowsable(false)
        .setIsPlayable(false)
        .build()
    )
    .build()

/**
 * The formatted form every tile is built from: the message renders as the subtitle, with newlines
 * collapsed to spaces — list rows are single-line.
 */
private fun createErrorMediaItem(mediaId: String, formatted: FormattedNavigationError): MediaItem =
  createErrorMediaItem(
    mediaId = mediaId,
    title = formatted.title,
    subtitle = formatted.message?.replace('\n', ' ') ?: "",
  )

/**
 * Builds the offline tile from the app's formatted `network-error` copy for the path being served,
 * so one browse list never mixes the app's locale with the device's.
 */
internal fun createOfflineMediaItem(formatted: FormattedNavigationError): MediaItem =
  createErrorMediaItem(BrowserPathHelper.OFFLINE_PATH, formatted)

/**
 * Builds the tile a failed browse or search serves, from the formatted copy for the error the
 * request's exception mapped to. The true offline case is handled separately by the networkMonitor
 * guards; anything reaching a catch block is an online-but-failed request (e.g. server down, bad
 * status), so it must NOT be labelled "no internet connection".
 */
internal fun createBrowseErrorMediaItem(formatted: FormattedNavigationError): MediaItem =
  createErrorMediaItem(BrowserPathHelper.ERROR_PATH, formatted)

/** The navigation error a browse or search served while offline raises. */
internal fun offlineError(): NavigationError =
  NavigationError(NavigationErrorType.NETWORK_ERROR, "", null, null)

/** The failure a re-read of the browse-error tile stands on, its own exception long gone. */
internal fun browseFailureError(): NavigationError =
  NavigationError(NavigationErrorType.UNKNOWN_ERROR, "", null, null)
