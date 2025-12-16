package com.audiobrowser.player

import android.os.Bundle
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Rating
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.audiobrowser.util.BrowserPathHelper
import com.audiobrowser.util.RatingFactory
import com.audiobrowser.util.ResolvedTrackFactory
import com.audiobrowser.util.TrackFactory
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.margelo.nitro.audiobrowser.FavoriteChangedEvent
import com.margelo.nitro.audiobrowser.NotificationButtonLayout
import com.margelo.nitro.audiobrowser.PlayerCapabilities
import com.margelo.nitro.audiobrowser.RemoteSetRatingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future
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

  // Track which controllers are subscribed to which media IDs
  private val parentIdSubscriptions =
    mutableMapOf<String, MutableSet<MediaSession.ControllerInfo>>()
  private var mediaLibrarySession: MediaLibraryService.MediaLibrarySession? = null

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
  private fun createOfflineMediaItem(): MediaItem {
    val errorTitle = player.context.getString(com.audiobrowser.R.string.audio_browser_offline_error)
    val errorSubtitle =
      player.context.getString(com.audiobrowser.R.string.audio_browser_offline_error_subtitle)
    return MediaItem.Builder()
      .setMediaId(BrowserPathHelper.OFFLINE_PATH)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(errorTitle)
          .setSubtitle(errorSubtitle)
          .setIsBrowsable(false)
          .setIsPlayable(false)
          .build()
      )
      .build()
  }

  fun updateMediaSession(
    mediaSession: MediaSession,
    capabilities: PlayerCapabilities,
    notificationButtons: NotificationButtonLayout?,
    searchAvailable: Boolean,
  ) {
    // Store as MediaLibrarySession for notifyChildrenChanged support
    this.mediaLibrarySession = mediaSession as? MediaLibraryService.MediaLibrarySession
    commandManager.updateMediaSession(
      mediaSession,
      capabilities,
      notificationButtons,
      searchAvailable,
    )
  }

  override fun onConnect(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
  ): MediaSession.ConnectionResult {
    Timber.Forest.d("MediaSession connect: ${controller.packageName}")
    return commandManager.buildConnectionResult(session)
  }

  /** Updates the favorite state of the current track and emits onFavoriteChanged. */
  private fun setFavorited(favorited: Boolean) {
    val currentTrack = player.currentTrack ?: return

    // Update native favorites cache (only tracks with src can be favorited)
    currentTrack.src?.let { src -> player.browser?.browserManager?.updateFavorite(src, favorited) }

    player.setActiveTrackFavorited(favorited)
    val event = FavoriteChangedEvent(player.currentTrack ?: currentTrack, favorited)
    player.callbacks?.onFavoriteChanged(event)
  }

  override fun onCustomCommand(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
    command: SessionCommand,
    args: Bundle,
  ): ListenableFuture<SessionResult> {
    // Handle favorite button tap
    if (command.customAction == MediaSessionCommandManager.CUSTOM_ACTION_FAVORITE) {
      val currentFavorited = player.currentTrack?.favorited ?: false
      Timber.d("Favorite button tapped - toggling from $currentFavorited to ${!currentFavorited}")
      setFavorited(!currentFavorited)
      return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
    if (rating is HeartRating) {
      setFavorited(rating.isHeart)
    }

    // Also emit onRemoteSetRating for listeners
    RatingFactory.media3ToBridge(rating)?.let {
      val event = RemoteSetRatingEvent(it)
      player.callbacks?.onRemoteSetRating(event)
    }
    return super.onSetRating(session, controller, rating)
  }

  override fun onGetLibraryRoot(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    params: MediaLibraryService.LibraryParams?,
  ): ListenableFuture<LibraryResult<MediaItem>> {

    // Look into:
    // KEY_ROOT_HINT_MEDIA_HOST_VERSION
    // KEY_ROOT_HINT_MEDIA_SESSION_API
    // BROWSER_ROOT_HINTS_KEY_MEDIA_ART_SIZE_PIXELS
    // BROWSER_ROOT_HINTS_KEY_CUSTOM_BROWSER_ACTION_LIMIT
    // BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT
    // KEY_ROOT_HINT_MAX_QUEUE_ITEMS_WHILE_RESTRICTED

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
      val browserManager =
        player
          .awaitBrowser()
          .also { Timber.d("Browser ready, proceeding with onGetChildren") }
          .browserManager

      // Show offline error when offline:
      if (
        !player.networkMonitor.isOnline.value && browserManager.config.androidControllerOfflineError
      ) {
        Timber.w("Network offline - returning error message for: $parentId")
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
            browserManager.queryTabs().take(4).map { tab -> TrackFactory.toMedia3(tab) }
          } else {
            // Resolve the specific path and get its children
            val resolvedTrack = browserManager.resolve(parentId)

            // Convert children to MediaItems (url is already set to contextual URL)
            resolvedTrack.children?.map { track -> TrackFactory.toMedia3(track) }
              ?: throw IllegalStateException(
                "Expected browsed ResolvedTrack to have a children array"
              )
          }

        LibraryResult.ofItemList(ImmutableList.copyOf(children.paginate(page, pageSize)), params)
      } catch (e: Exception) {
        Timber.e(e, "Error getting children for parentId: $parentId")
        LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
      }
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

      try {
        val resolvedTrack = browserManager.resolve(mediaId)
        val mediaItem = ResolvedTrackFactory.toMedia3(resolvedTrack)
        LibraryResult.ofItem(mediaItem, null)
      } catch (e: Exception) {
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

    parentIdSubscriptions.getOrPut(parentId) { mutableSetOf() }.add(browser)
    return super.onSubscribe(session, browser, parentId, params)
  }

  override fun onUnsubscribe(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    parentId: String,
  ): ListenableFuture<LibraryResult<Void>> {
    Timber.d("onUnsubscribe: ${browser.packageName}, parentId = $parentId")

    parentIdSubscriptions[parentId]?.remove(browser)
    if (parentIdSubscriptions[parentId]?.isEmpty() == true) {
      parentIdSubscriptions.remove(parentId)
    }

    return super.onUnsubscribe(session, browser, parentId)
  }

  /**
   * Notifies all subscribed controllers to refresh their content. Called internally when network
   * state changes to refresh all subscribed paths.
   */
  private fun notifySubscribedChildrenChanged() {
    parentIdSubscriptions.keys.forEach { parentId ->
      mediaLibrarySession?.notifyChildrenChanged(parentId, Int.MAX_VALUE, null)
    }
  }

  /**
   * Notifies external controllers that content at the given path has changed. Controllers
   * subscribed to this path will refresh their UI.
   *
   * @param path The path where content has changed
   */
  fun notifyContentChanged(path: String) {
    Timber.d("Notifying content changed for path: $path")
    mediaLibrarySession?.notifyChildrenChanged(path, Int.MAX_VALUE, null)
  }

  /**
   * Called when the browser becomes available after a cold start. Notifies all subscribed
   * controllers to refresh their content.
   */
  fun notifyBrowserReady() {
    Timber.d("Browser ready - notifying ${parentIdSubscriptions.size} subscribed paths")
    notifySubscribedChildrenChanged()
  }

  override fun onSearch(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    params: MediaLibraryService.LibraryParams?,
  ): ListenableFuture<LibraryResult<Void>> {
    Timber.d("onSearch: ${browser.packageName}, query = $query")
    return scope.future {
      val browserManager = player.browser?.browserManager

      if (browserManager == null) {
        Timber.w("AudioBrowser not registered - search not available")
        return@future LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
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
      val browserManager = player.browser?.browserManager

      if (browserManager == null) {
        Timber.w("AudioBrowser not registered - search not available")
        return@future LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
      }

      try {
        // Get cached search results from BrowserManager
        browserManager.getCachedSearchResults(query)?.let { tracks ->
          // Convert to MediaItems
          val mediaItems =
            tracks.map { track ->
              Timber.d("Search result: ${track.title} (url=${track.url}, src=${track.src})")
              TrackFactory.toMedia3(track)
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
      "onSetMediaItems: ${controller.packageName}, count=${mediaItems.size}, mediaId=${mediaItems[0].mediaId}, uri=${mediaItems[0].localConfiguration?.uri}, title=${mediaItems[0].mediaMetadata.title}"
    )

    return scope.future {
      // Check if this is a single contextual URL that matches the current queue source
      if (mediaItems.size == 1) {
        val mediaId = mediaItems[0].mediaId
        if (BrowserPathHelper.isContextual(mediaId)) {
          val parentPath = BrowserPathHelper.stripTrackId(mediaId)
          val trackId = BrowserPathHelper.extractTrackId(mediaId)

          // Check if queue already came from this parent path - just skip to the track
          if (trackId != null && parentPath == player.queueSourcePath) {
            val index = player.tracks.indexOfFirst { it.src == trackId }
            if (index >= 0) {
              Timber.d("Queue already from $parentPath, skipping to index $index")
              // Return the existing queue items with the new start index
              val existingItems = player.tracks.map { TrackFactory.toMedia3(it) }
              return@future MediaSession.MediaItemsWithStartPosition(
                existingItems,
                index,
                startPositionMs,
              )
            }
          }
        }
      }

      // Wait for browser to be registered if it's not available yet
      val browserManager = player.awaitBrowser().browserManager
      val result =
        browserManager.resolveMediaItemsForPlayback(mediaItems, startIndex, startPositionMs)

      // If this was a contextual URL expansion, track the source path
      if (mediaItems.size == 1) {
        val mediaId = mediaItems[0].mediaId
        if (BrowserPathHelper.isContextual(mediaId)) {
          val parentPath = BrowserPathHelper.stripTrackId(mediaId)
          withContext(Dispatchers.Main) { player.queueSourcePath = parentPath }
        }
      }

      result
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
   * @param playback True if playback should start, false if just gathering info for UI
   * @see https://developer.android.com/media/media3/session/background-playback#resumption
   */
  override fun onPlaybackResumption(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    playback: Boolean,
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    Timber.d("${controller.packageName}, playback=$playback")

    return scope.future {
      // Get persisted state - only restore player settings if we're actually going to play
      // When playback=false, system is just gathering info for UI (e.g., boot-time notification)
      val state =
        if (playback) {
          // restore() sets player properties which must happen on main thread
          withContext(Dispatchers.Main) { player.playbackStateStore.restore() }
        } else {
          player.playbackStateStore.get()
        }
          ?: run {
            Timber.w("No persisted playback state found")
            throw IllegalStateException("No playback state to resume")
          }

      val url = state.track.url
      Timber.d("Resuming from url=$url, positionMs=${state.positionMs}")

      // Wait for browser to be available (JS needs to have configured it)
      val browserManager = player.awaitBrowser().browserManager

      // Try to expand the URL into a full queue
      val expanded = url?.let { browserManager.expandQueueFromContextualUrl(it) }

      if (expanded != null) {
        val (tracks, selectedIndex) = expanded
        Timber.d(
          "Restored ${tracks.size} tracks, starting at index $selectedIndex at ${state.positionMs}ms"
        )

        // Track the source path if this was a contextual URL expansion
        if (BrowserPathHelper.isContextual(url)) {
          val parentPath = BrowserPathHelper.stripTrackId(url)
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
