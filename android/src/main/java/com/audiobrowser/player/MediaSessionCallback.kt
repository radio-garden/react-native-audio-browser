package com.audiobrowser.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Rating
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.audiobrowser.util.RatingFactory
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.margelo.nitro.audiobrowser.Capability
import com.margelo.nitro.audiobrowser.RemoteSetRatingEvent
import java.util.UUID
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * MediaLibrarySession callback that handles all media session interactions. All logic is handled
 * directly by the AudioBrowser.
 */
class MediaSessionCallback(private val player: Player) :
  MediaLibraryService.MediaLibrarySession.Callback {
  private val commandManager = MediaSessionCommandManager()

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
    Timber.Forest.d("onGetLibraryRoot: { package: ${browser.packageName} }")
    return Futures.immediateFuture(
      LibraryResult.ofItem(
        MediaItem.Builder()
          .setMediaId("__ROOT__")
          .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
              .setTitle("Root")
              .setIsBrowsable(true)
              .setIsPlayable(false)
              .build()
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
    val requestId = UUID.randomUUID().toString()
    val future = com.google.common.util.concurrent.SettableFuture.create<List<MediaItem>>()
    // Store the future for later resolution
    player.pendingGetChildrenRequests[requestId] = future
    // Emit event to JavaScript via callbacks
    player.callbacks?.onGetChildrenRequest(requestId, parentId, page, pageSize)
    return Futures.transform(
      future,
      { items -> LibraryResult.ofItemList(ImmutableList.copyOf(items), null) },
      MoreExecutors.directExecutor(),
    )
  }

  override fun onGetItem(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    mediaId: String,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    Timber.Forest.d("onGetItem: ${browser.packageName}, mediaId = $mediaId")
    val requestId = UUID.randomUUID().toString()
    val future = com.google.common.util.concurrent.SettableFuture.create<MediaItem?>()
    // Store the future for later resolution
    player.pendingGetItemRequests[requestId] = future
    // Emit event to JavaScript via callbacks
    player.callbacks?.onGetItemRequest(requestId, mediaId)
    return Futures.transform(
      future,
      { item ->
        if (item != null) {
          LibraryResult.ofItem(item, null)
        } else {
          LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }
      },
      MoreExecutors.directExecutor(),
    )
  }

  override fun onSearch(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    params: MediaLibraryService.LibraryParams?,
  ): ListenableFuture<LibraryResult<Void>> {
    Timber.Forest.d("onSearch: ${browser.packageName}, query = $query")
    // Emit event to JavaScript via callbacks for search initiation
    val requestId = UUID.randomUUID().toString()
    val extrasMap =
      params?.extras?.let { bundle ->
        mutableMapOf<String, Any>().apply {
          for (key in bundle.keySet()) {
            when (val value = bundle.get(key)) {
              is String -> put(key, value)
              is Int -> put(key, value)
              is Double -> put(key, value)
              is Boolean -> put(key, value)
            // Add other types as needed
            }
          }
        }
      }
    player.callbacks?.onSearchRequest(requestId, query, extrasMap)
    return super.onSearch(session, browser, query, params)
  }

  override fun onSetMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: MutableList<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    Timber.Forest.d(
      "onSetMediaItems: ${controller.packageName}, mediaId=${mediaItems[0].mediaId}, uri=${mediaItems[0].localConfiguration?.uri}, title=${mediaItems[0].mediaMetadata.title}"
    )

    val resolvedItems =
      mediaItems.map { mediaItem ->
        val mediaId = mediaItem.mediaId
        val fullMediaItem = player.mediaItemById[mediaId]
        if (fullMediaItem != null) {
          Timber.Forest.d("Found full MediaItem for mediaId: $mediaId")
          fullMediaItem
        } else {
          Timber.Forest.d("No full MediaItem found for mediaId: $mediaId, using original")
          mediaItem
        }
      }

    try {
      player.clear()
      player.add(com.audiobrowser.util.TrackFactory.fromMedia3(resolvedItems))
      player.skipTo(startIndex)
      player.seekTo(startPositionMs, TimeUnit.MILLISECONDS)
      player.play()
    } catch (e: Exception) {
      Timber.Forest.e(e, "Error in onSetMediaItems")
    }

    return Futures.immediateFuture(
      MediaSession.MediaItemsWithStartPosition(resolvedItems, startIndex, startPositionMs)
    )
  }
}
