package com.audiobrowser.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Rating
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.audiobrowser.util.RatingFactory
import com.audiobrowser.util.ResolvedTrackFactory
import com.audiobrowser.util.TrackFactory
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.margelo.nitro.audiobrowser.Capability
import com.margelo.nitro.audiobrowser.RemoteSetRatingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future
import timber.log.Timber

/**
 * MediaLibrarySession callback that handles all media session interactions. All logic is handled
 * directly by the AudioBrowser.
 */
class MediaSessionCallback(private val player: Player) :
  MediaLibraryService.MediaLibrarySession.Callback {
  companion object {
    private const val ROOT_ID = "__ROOT__"
    private const val ROOT_ID_RECENT = "__ROOT_RECENT__"
  }

  private val commandManager = MediaSessionCommandManager()
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  fun updateMediaSession(
    mediaSession: MediaSession,
    capabilities: List<Capability>,
    notificationCapabilities: List<Capability>?,
  ) {
    commandManager.updateMediaSession(mediaSession, capabilities, notificationCapabilities)
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
    commandManager.handleCustomCommand(command, player)
    return super.onCustomCommand(session, controller, command, args)
  }

  override fun onSetRating(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
    rating: Rating,
  ): ListenableFuture<SessionResult> {
    RatingFactory.media3ToBridge(rating)?.let {
      val event = RemoteSetRatingEvent(it)
      player.callbacks?.handleRemoteSetRating(event)
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
          .setMediaId(ROOT_ID)
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
    Timber.Forest.d("onGetChildren: {parentId: $parentId, page: $page, pageSize: $pageSize }")
    return scope.future {
      val browserManager = player.browser?.browserManager

      if (browserManager == null) {
        Timber.w("AudioBrowser not registered - media browsing not available")
        return@future LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
      }

      try {
        val children =
          if (parentId == ROOT_ID_RECENT) {
            // TODO: implement recent media items
            emptyList<MediaItem>()
          } else if (parentId == ROOT_ID) {
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

        // Apply pagination if needed
        val paginatedChildren =
          if (pageSize > 0) {
            val startIndex = page * pageSize
            children.drop(startIndex).take(pageSize)
          } else {
            children
          }

        LibraryResult.ofItemList(ImmutableList.copyOf(paginatedChildren), params)
      } catch (e: Exception) {
        Timber.e(e, "Error getting children for parentId: $parentId")
        val errorCode =
          when (e) {
            is com.audiobrowser.browser.ContentNotFoundException -> SessionError.ERROR_BAD_VALUE
            is com.audiobrowser.browser.HttpStatusException -> {
              when (e.statusCode) {
                404 -> SessionError.ERROR_BAD_VALUE
                else -> SessionError.ERROR_UNKNOWN
              }
            }
            is com.audiobrowser.browser.NetworkException -> SessionError.ERROR_UNKNOWN
            else -> SessionError.ERROR_UNKNOWN
          }
        LibraryResult.ofError(errorCode)
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
      val browserManager = player.browser?.browserManager

      if (browserManager == null) {
        Timber.w("AudioBrowser not registered - media browsing not available")
        return@future LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
      }

      // Handle special root IDs
      if (mediaId == ROOT_ID || mediaId == ROOT_ID_RECENT) {
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

  override fun onSearch(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    params: MediaLibraryService.LibraryParams?,
  ): ListenableFuture<LibraryResult<Void>> {
    Timber.Forest.d("onSearch: ${browser.packageName}, query = $query")
    return scope.future {
      session.notifySearchResultChanged(
        browser,
        query,
        // TODO: this should be the count of the returned results from BrowserManager.search:
        0,
        params
      )
      // TODO: return LibraryResult.ofError when search is not configured
      LibraryResult.ofVoid()
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
      val browserManager =
        player.browser?.browserManager
          ?: throw IllegalStateException("BrowserManager not available")

      // Resolve media items for playback (handles queue expansion and cache lookups)
      browserManager.resolveMediaItemsForPlayback(mediaItems, startIndex, startPositionMs)
    }
  }
}
