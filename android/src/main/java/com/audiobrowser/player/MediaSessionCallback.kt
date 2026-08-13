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
import com.audiobrowser.extension.identity
import com.audiobrowser.extension.toTrack
import com.audiobrowser.util.BrowserPathHelper
import com.audiobrowser.util.RatingFavorites
import com.audiobrowser.util.TrackFactory
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.margelo.nitro.audiobrowser.Gate
import com.margelo.nitro.audiobrowser.GateEvent
import com.margelo.nitro.audiobrowser.GateReason
import com.margelo.nitro.audiobrowser.MediaReference
import com.margelo.nitro.audiobrowser.NativeGateRequest
import com.margelo.nitro.audiobrowser.PlayerCapabilities
import com.margelo.nitro.audiobrowser.RemoteButtonLayout
import com.margelo.nitro.audiobrowser.SearchParams
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
 * MediaLibrarySession callback that handles all media session interactions. All logic is handled
 * directly by the AudioBrowser.
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
    // Observe network state changes and notify subscribers
    player.networkMonitor.observeOnline(scope) { _ -> notifySubscribedChildrenChanged() }
  }

  /**
   * Apply pagination to a list of items. If pageSize is 0 or MAX_VALUE (Android Auto default),
   * returns the full list.
   */
  private fun <T> List<T>.paginate(page: Int, pageSize: Int): List<T> {
    return if (pageSize in 1 until Int.MAX_VALUE) {
      this.drop(page * pageSize).take(pageSize)
    } else {
      this
    }
  }

  /** Creates an offline error MediaItem. */
  private fun createOfflineMediaItem(): MediaItem =
    createErrorMediaItem(
      mediaId = BrowserPathHelper.OFFLINE_PATH,
      title = player.context.getString(com.audiobrowser.R.string.audio_browser_offline_error),
      subtitle =
        player.context.getString(com.audiobrowser.R.string.audio_browser_offline_error_subtitle),
    )

  /**
   * Builds a non-browsable, non-playable [MediaItem] used to surface an error inside an Android
   * Auto / AAOS browse list. Rendered as a greyed-out, non-interactive tile, which is the only
   * side-effect-free way to communicate a browse failure to legacy controllers (Media3 drops
   * [LibraryResult.ofError] on the legacy browse bridge, leaving an empty "No items" screen).
   */
  private fun createErrorMediaItem(mediaId: String, title: String, subtitle: String): MediaItem =
    MediaItem.Builder()
      .setMediaId(mediaId)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(title)
          .setSubtitle(subtitle)
          // Non-browsable, non-playable. Android Auto ignores these flags and still drills into a
          // tapped tile, but onGetChildren returns an empty list for the sentinel paths so it's a
          // harmless "No Items" dead-end rather than an endless stack of error pages.
          .setIsBrowsable(false)
          .setIsPlayable(false)
          .build()
      )
      .build()

  /**
   * Builds the gate tile from a per-request chrome: while a gate is active, tabs stay visible but a
   * gated browse/search level serves this single tile (see the gate checks in [onGetChildren] /
   * [onGetSearchResult]). Same shape as the error tiles — non-browsable, non-playable — and
   * deliberately NOT accompanied by a SessionError: a gate is deliberate app state, not a failure.
   * The message renders as the tile's subtitle (newlines collapse to spaces — list rows are
   * single-line).
   */
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

  private fun createGateMediaItem(gate: Gate): MediaItem =
    createErrorMediaItem(
      mediaId = BrowserPathHelper.GATE_PATH,
      title = gate.title,
      subtitle = gate.message?.replace('\n', ' ') ?: "",
    )

  /**
   * Builds a generic "something went wrong" error tile for a browse failure. The true offline case
   * is handled separately by the [networkMonitor] guard in [onGetChildren]; anything reaching the
   * catch block is an online-but-failed request (e.g. server down, bad status), so it must NOT be
   * labelled "no internet connection".
   */
  private fun createBrowseErrorMediaItem(): MediaItem =
    createErrorMediaItem(
      mediaId = BrowserPathHelper.ERROR_PATH,
      title = player.context.getString(com.audiobrowser.R.string.audio_browser_browse_error),
      subtitle =
        player.context.getString(com.audiobrowser.R.string.audio_browser_browse_error_subtitle),
    )

  fun updateMediaSession(
    mediaSession: MediaSession,
    capabilities: PlayerCapabilities,
    remoteButtonLayout: RemoteButtonLayout?,
    searchAvailable: Boolean,
    forwardJumpInterval: Double,
    backwardJumpInterval: Double,
  ) {
    // Store as MediaLibrarySession for notifyChildrenChanged support
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
    // Handle favorite button tap. Report an honest result: a success here makes
    // the controller flip its heart optimistically, so a no-op (no current
    // track) must say INVALID_STATE instead of success-then-revert.
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
    // Extract artwork size hint from root hints (e.g., from Android Auto)
    // TODO: Also consider these other root hints in the future:
    // - KEY_ROOT_HINT_MEDIA_HOST_VERSION
    // - KEY_ROOT_HINT_MEDIA_SESSION_API
    // - BROWSER_ROOT_HINTS_KEY_CUSTOM_BROWSER_ACTION_LIMIT
    // - BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT
    // - KEY_ROOT_HINT_MAX_QUEUE_ITEMS_WHILE_RESTRICTED
    params?.extras?.getInt(MediaConstants.EXTRAS_KEY_MEDIA_ART_SIZE_PIXELS, 0)?.let { size ->
      if (size > 0) {
        artworkSizeHintPixels = size
        Timber.d("Received artwork size hint: ${size}px from ${browser.packageName}")
      }
    }

    if (params?.isRecent == true) {
      // TODO: support recent queries through something like onRecent and return a MediaItem with
      // .setMediaId("__RECENT__") here - when onRecent is not configured we can keep returning an
      // error here:

      // The service currently does not support playback resumption. Tell System UI by returning
      // an error of type 'RESULT_ERROR_NOT_SUPPORTED' for a `params.isRecent` request. See
      // https://github.com/androidx/media/issues/355
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
      "onGetChildren: {parentId: $parentId, page: $page, pageSize: $pageSize, isSearchPath: ${BrowserPathHelper.isSpecialPath(parentId)} }"
    )
    return scope.future {
      // Wait for browser to be registered if it's not available yet
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

      // Show offline error when offline:
      if (
        !player.networkMonitor.isOnline.value && browserManager.config.androidControllerOfflineError
      ) {
        Timber.w("Network offline - returning error message for: $parentId")
        sendBrowseError(session, browser, offline = true)
        return@future LibraryResult.ofItemList(ImmutableList.of(createOfflineMediaItem()), params)
      }

      try {
        val children =
          if (parentId == BrowserPathHelper.RECENT_PATH) {
            // TODO: implement recent media items
            emptyList<MediaItem>()
          } else if (parentId == BrowserPathHelper.ROOT_PATH) {
            // Return tabs as root children (limited to 4 for automotive platform compatibility)
            // TODO: Check what Android Auto does with empty tabs list - may need to return error?
            val tabs = browserManager.queryTabs()
            if (tabs.size > 4) {
              Timber.w(
                "Root has ${tabs.size} tabs; dropping ${tabs.size - 4} (Android Auto root limit)"
              )
            }
            toMediaItems(tabs.take(4))
          } else {
            // Resolve the specific path and get its children
            val resolvedTrack = browserManager.resolve(parentId)

            // Convert children to MediaItems (path is already set to contextual path)
            val trackChildren =
              resolvedTrack.children
                ?: throw IllegalStateException(
                  "Expected browsed ResolvedTrack to have a children array"
                )
            toMediaItems(trackChildren.toList())
          }

        LibraryResult.ofItemList(ImmutableList.copyOf(children.paginate(page, pageSize)), params)
      } catch (e: Exception) {
        // A cancelled browse (controller disconnected) is not a failure — don't render it as an
        // error tile. awaitBrowser's TimeoutCancellationException IS a real failure, keep that.
        if (e is CancellationException && e !is TimeoutCancellationException) throw e
        Timber.e(e, "Error getting children for parentId: $parentId")
        // Surface an error tile instead of a bare ofError(): Media3 drops the error message on the
        // legacy browse bridge, which would otherwise leave an empty "No items" screen in Android
        // Auto. See https://github.com/androidx/media/issues/2901
        //
        // Verified on a head unit (2026-06): the AA browse list NEVER renders error text — not for
        // transient sendError, sticky non-fatal replication, or fatal replication
        // (setLibraryErrorReplicationMode). Fatal replication does show the message on the
        // playback screen, but presents the session as STATE_ERROR with no actions, hiding the
        // now-playing item and transport controls — not worth it. Tiles remain the only in-browse
        // signal we control.
        sendBrowseError(session, browser, offline = false)
        LibraryResult.ofItemList(ImmutableList.of(createBrowseErrorMediaItem()), params)
      }
    }
  }

  /**
   * Sends a non-fatal [SessionError] to the controller whose browse failed, complementing the error
   * tile. Media3 browsers receive it via MediaController.Listener.onError; for legacy controllers
   * Media3 transiently attaches the error code/message to the platform session's playback state
   * without entering STATE_ERROR.
   *
   * NOTE: current Android Auto renders NOTHING for this (verified on a head unit — see
   * androidx/media#2901; the transient state is cleared microseconds after being set). It is kept
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
   * Converts tracks to MediaItems for browse delivery, routing http(s) artwork through the
   * content:// provider so Android Auto can load it via the ArtworkContentProvider. Image-row
   * tracks (a CarPlay-only rendering) are expanded into their items as regular grouped rows first —
   * see [TrackFactory.expandImageRows].
   */
  private fun toMediaItems(tracks: List<Track>): List<MediaItem> {
    val registry = player.browseArtworkRegistry
    val authority = com.audiobrowser.util.ArtworkUris.authorityFor(player.context.packageName)
    return TrackFactory.expandImageRows(tracks).map {
      TrackFactory.toBrowseMediaItem(it, registry, authority)
    }
  }

  override fun onGetItem(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    mediaId: String,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    Timber.Forest.d("onGetItem: ${browser.packageName}, mediaId = $mediaId")
    return scope.future {
      // Handle special paths first (these don't need browser)
      if (mediaId == BrowserPathHelper.OFFLINE_PATH) {
        return@future LibraryResult.ofItem(createOfflineMediaItem(), null)
      }

      // Return the error tile as a non-browsable item so tapping it can't re-browse into a
      // failing path (which would push another error page onto the stack).
      if (mediaId == BrowserPathHelper.ERROR_PATH) {
        return@future LibraryResult.ofItem(createBrowseErrorMediaItem(), null)
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

      if (mediaId == BrowserPathHelper.ROOT_PATH || mediaId == BrowserPathHelper.RECENT_PATH) {
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

      // Wait for browser to be registered if it's not available yet
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
   * Notifies all subscribed controllers to refresh their content. Called internally when network
   * state changes to refresh all subscribed paths.
   *
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
   * Notifies external controllers that content at the given path has changed. Controllers
   * subscribed to this path will refresh their UI. Safe to call from any thread.
   *
   * @param path The path where content has changed
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

  /**
   * Called when the browser becomes available after a cold start. Notifies all subscribed
   * controllers to refresh their content.
   */
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
      // Wait for browser registration like onGetChildren does, so a cold-start voice search
      // doesn't fail before JS has configured the browser.
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

      // Check if search is configured
      if (!browserManager.config.hasSearch) {
        Timber.w("Search requested but no search source configured")
        return@future LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
      }

      try {
        // Execute search (automatically caches results at /__search?q=query)
        val searchResults = browserManager.search(query)
        val resultCount = searchResults.children?.size ?: 0

        Timber.d("Search completed: $resultCount results for query '$query'")

        // Notify Media3 of search results
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
        // Get cached search results from BrowserManager
        browserManager.getCachedSearchResults(query)?.let { tracks ->
          // Convert to MediaItems
          val searchAuthority =
            com.audiobrowser.util.ArtworkUris.authorityFor(player.context.packageName)
          val mediaItems =
            tracks.map { track ->
              Timber.d("Search result: ${track.title} (path=${track.path}, src=${track.src})")
              TrackFactory.toBrowseMediaItem(track, player.browseArtworkRegistry, searchAuthority)
            }

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

      // Helper: returns the current player state unchanged so Media3 doesn't modify playback.
      // ExoPlayer is main-thread confined; this future runs on IO, so hop to Main for the reads.
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

      // Check if this is a single item from the current queue source
      if (singleContextualPath != null) {
        val parentPath = BrowserPathHelper.stripTrackId(singleContextualPath)
        val trackId = BrowserPathHelper.extractTrackId(singleContextualPath)

        // Check if queue already came from this parent path - just skip to the track
        if (trackId != null && parentPath == player.queueSourcePath) {
          val queueTracks = withContext(Dispatchers.Main) { player.tracks }
          val index = queueTracks.indexOfFirst { it.identity == trackId }
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
              // Return the existing queue items with the new start index
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
        // If this was a contextual path expansion, track the source path (only for default
        // behavior)
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
   * @param mediaSession The media session
   * @param controller The controller requesting resumption
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

      // isForPlayback == false is the device-boot-time notification case: network may be
      // unavailable and we must not start playback. Return just the locally-stored track with its
      // already-local metadata; skip the settings restore and network queue expansion below.
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

      // Wait for browser to be available (JS needs to have configured it)
      val browserManager = player.awaitBrowser().browserManager

      // Try to expand the path into a full queue
      val expanded = path?.let { browserManager.expandQueueFromContextualPath(it) }

      if (expanded != null) {
        val (tracks, selectedIndex) = expanded
        Timber.d(
          "Restored ${tracks.size} tracks, starting at index $selectedIndex at ${state.positionMs}ms"
        )

        // Track the source path if this was a contextual path expansion
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
        // Fallback: play just the stored track
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
