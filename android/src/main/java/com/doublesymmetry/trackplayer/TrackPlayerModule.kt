package com.doublesymmetry.trackplayer

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.media3.common.Metadata
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.media3.session.legacy.RatingCompat
import com.doublesymmetry.trackplayer.event.ControllerConnectedEvent
import com.doublesymmetry.trackplayer.event.ControllerDisconnectedEvent
import com.doublesymmetry.trackplayer.event.PlaybackActiveTrackChangedEvent
import com.doublesymmetry.trackplayer.event.PlaybackErrorEvent
import com.doublesymmetry.trackplayer.event.PlaybackPlayWhenReadyChangedEvent
import com.doublesymmetry.trackplayer.event.PlaybackPlayingStateEvent
import com.doublesymmetry.trackplayer.event.PlaybackProgressUpdatedEvent
import com.doublesymmetry.trackplayer.event.PlaybackQueueEndedEvent
import com.doublesymmetry.trackplayer.event.PlaybackRepeatModeChangedEvent
import com.doublesymmetry.trackplayer.event.RemoteJumpBackwardEvent
import com.doublesymmetry.trackplayer.event.RemoteJumpForwardEvent
import com.doublesymmetry.trackplayer.event.RemoteSeekEvent
import com.doublesymmetry.trackplayer.event.RemoteSetRatingEvent
import com.doublesymmetry.trackplayer.extension.NumberExt.Companion.toSeconds
import com.doublesymmetry.trackplayer.model.PlaybackMetadata
import com.doublesymmetry.trackplayer.model.PlaybackState
import com.doublesymmetry.trackplayer.model.PlayerSetupOptions
import com.doublesymmetry.trackplayer.model.PlayerUpdateOptions
import com.doublesymmetry.trackplayer.model.TrackFactory
import com.doublesymmetry.trackplayer.option.PlayerRepeatMode
import com.doublesymmetry.trackplayer.util.BundleUtils
import com.doublesymmetry.trackplayer.util.MetadataAdapter
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit
import javax.annotation.Nonnull
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

@ReactModule(name = TrackPlayerModule.NAME)
class TrackPlayerModule(reactContext: ReactApplicationContext) :
  NativeTrackPlayerSpec(reactContext), ServiceConnection {
  private lateinit var browser: MediaBrowser
  private var mediaBrowserFuture: ListenableFuture<MediaBrowser>? = null
  private var setupOptions = PlayerSetupOptions()
  private var playerSetUpPromise: Promise? = null
  private val mainScope = MainScope()
  private var connectedService: TrackPlayerService? = null
  private val context = reactContext
  private val trackFactory =
    TrackFactory(context) { connectedService?.player?.ratingType ?: RatingCompat.RATING_NONE }

  // Media browser event buffering
  private var mediaBrowserReady = false
  private val eventBuffer = mutableListOf<BufferedEvent>()
  private val bufferLock = Any()
  private val maxBufferSize = 50

  sealed class BufferedEvent {
    data class GetItem(val requestId: String, val mediaId: String) : BufferedEvent()

    data class GetChildren(
      val requestId: String,
      val parentId: String,
      val page: Int,
      val pageSize: Int,
    ) : BufferedEvent()

    data class SearchResults(
      val requestId: String,
      val query: String,
      val extras: ReadableMap?,
      val page: Int,
      val pageSize: Int,
    ) : BufferedEvent()
  }

  @Nonnull
  override fun getName(): String {
    return NAME
  }

  companion object {
    const val NAME = "TrackPlayer"
  }

  override fun initialize() {
    Timber.d("TrackPlayerModule.initialize() called on instance: ${this.hashCode()}")
    Timber.d("React context: ${context.javaClass.simpleName}")

    // Auto-bind to service if it's already running (important for Android Auto scenarios)
    launchInScope {
      try {
        Timber.d(
          "Attempting to auto-bind to existing TrackPlayerService from module ${this@TrackPlayerModule.hashCode()}"
        )
        val intent = Intent(context, TrackPlayerService::class.java)
        val bound = context.bindService(intent, this@TrackPlayerModule, Context.BIND_AUTO_CREATE)
        Timber.d("Auto-bind result: $bound for module ${this@TrackPlayerModule.hashCode()}")

        if (!bound) {
          Timber.w("Failed to bind to TrackPlayerService - service may not be running")
        }
      } catch (e: Exception) {
        Timber.e(e, "Failed to auto-bind to TrackPlayerService during initialization")
      }
    }
  }

  override fun onServiceConnected(name: ComponentName, serviceBinder: IBinder) {
    launchInScope {
      connectedService =
        (serviceBinder as TrackPlayerService.LocalBinder).service.apply {
          player.setCallbacks(this@TrackPlayerModule.callbacks)
          player.setup(setupOptions)
        }

      val sessionToken =
        SessionToken(context, ComponentName(context, TrackPlayerService::class.java))
      mediaBrowserFuture = MediaBrowser.Builder(context, sessionToken).buildAsync()

      playerSetUpPromise?.resolve(null)
      playerSetUpPromise = null
    }
  }

  /** Called when a connection to the Service has been lost. */
  override fun onServiceDisconnected(name: ComponentName) {
    // Cancel all event observation coroutines when service disconnects
    mainScope.coroutineContext.cancelChildren()

    mediaBrowserFuture = null
    connectedService = null
    Timber.d("TrackPlayerModule.onServiceDisconnected() - module ${this.hashCode()} unregistered")
  }

  /* ****************************** API ****************************** */
  @SuppressLint("UnspecifiedRegisterReceiverFlag")
  override fun setupPlayer(data: ReadableMap?, promise: Promise) {
    launchInScope {
      setupOptions.updateFromBridge(data)

      connectedService?.let {
        it.player.setup(setupOptions)
        promise.resolve(null)
        return@launchInScope
      }

      // Service not connected yet, store promise for when it connects
      playerSetUpPromise = promise

      Timber.d("Binding to TrackPlayerService")
      context
        .bindService(
          Intent(context, TrackPlayerService::class.java),
          this@TrackPlayerModule,
          Context.BIND_AUTO_CREATE,
        )
        .let {
          Timber.d("context.bindService result: $it")
          if (!it) {
            playerSetUpPromise?.reject("SETUP_FAILED", "Failed to bind to TrackPlayerService")
            playerSetUpPromise = null
          }
        }
    }
  }

  override fun updateOptions(data: ReadableMap?): Unit = runBlockingOnMain {
    // Get current options and update only the fields provided in data
    val currentOptions = player.getOptions()
    val updatedOptions = currentOptions.copy()
    updatedOptions.updateFromBridge(data)
    player.applyOptions(updatedOptions)
  }

  override fun getOptions(): WritableMap = runBlockingOnMain { player.getOptions().toBridge() }

  override fun getRepeatMode(): String = runBlockingOnMain { player.repeatMode.string }

  override fun setRepeatMode(mode: String): Unit = runBlockingOnMain {
    PlayerRepeatMode.fromString(mode)?.let { repeatMode ->
      player.repeatMode = repeatMode
    }
  }

  override fun add(data: ReadableArray, insertBeforeIndex: Double?): Unit = runBlockingOnMain {
    val inputIndex = insertBeforeIndex?.toInt() ?: -1
    val tracks = trackFactory.tracksFromBridge(data)
    player.add(tracks, inputIndex)
  }

  override fun load(data: ReadableMap?): Unit = runBlockingOnMain {
    data?.let { player.load(trackFactory.fromBridge(it)) }
  }

  override fun move(fromIndex: Double, toIndex: Double): Unit = runBlockingOnMain {
    player.move(fromIndex.toInt(), toIndex.toInt())
  }

  override fun remove(data: ReadableArray?): Unit = runBlockingOnMain {
    Arguments.toList(data)?.map { (it as Number).toInt() }?.let { player.remove(it) }
  }

  override fun updateMetadataForTrack(index: Double, map: ReadableMap?): Unit = runBlockingOnMain {
    map?.let {
      val currentTrack = player.getTrack(index.toInt())
      val updatedTrack =
        currentTrack.updateMetadata(
          title = it.getString("title") ?: currentTrack.title,
          artist = it.getString("artist") ?: currentTrack.artist,
          album = it.getString("album") ?: currentTrack.album,
          artwork = it.getString("artwork") ?: currentTrack.artwork,
          date = it.getString("date") ?: currentTrack.date,
          genre = it.getString("genre") ?: currentTrack.genre,
          duration = if (it.hasKey("duration")) it.getDouble("duration") else currentTrack.duration,
          rating = BundleUtils.getRating(it, "rating", player.ratingType) ?: currentTrack.rating,
          mediaId = it.getString("mediaId") ?: currentTrack.mediaId,
        )
      player.replaceTrack(index.toInt(), updatedTrack)
    }
  }

  override fun updateNowPlayingMetadata(map: ReadableMap?): Unit = runBlockingOnMain {
    if (player.isEmpty) {
      throw Exception("There is no current item in the player")
    }

    map?.let {
      val currentIndex = player.currentIndex ?: throw Exception("There is no current track")
      val currentTrack = player.currentTrack ?: throw Exception("There is no current track")
      val updatedTrack =
        currentTrack.updateMetadata(
          title = it.getString("title") ?: currentTrack.title,
          artist = it.getString("artist") ?: currentTrack.artist,
          album = it.getString("album") ?: currentTrack.album,
          artwork = it.getString("artwork") ?: currentTrack.artwork,
          date = it.getString("date") ?: currentTrack.date,
          genre = it.getString("genre") ?: currentTrack.genre,
          duration = if (it.hasKey("duration")) it.getDouble("duration") else currentTrack.duration,
          rating = BundleUtils.getRating(it, "rating", player.ratingType) ?: currentTrack.rating,
          mediaId = it.getString("mediaId") ?: currentTrack.mediaId,
        )
      player.replaceTrack(currentIndex, updatedTrack)
    }
  }

  override fun removeUpcomingTracks() = runBlockingOnMain { player.removeUpcomingTracks() }

  override fun skip(index: Double, initialTime: Double?) = runBlockingOnMain {
    player.skipTo(index.toInt())

    if (initialTime != null && initialTime >= 0) {
      player.seekTo((initialTime * 1000).toLong(), TimeUnit.MILLISECONDS)
    }
  }

  override fun skipToNext(initialTime: Double?) = runBlockingOnMain {
    player.next()

    if (initialTime != null && initialTime >= 0) {
      player.seekTo((initialTime * 1000).toLong(), TimeUnit.MILLISECONDS)
    }
  }

  override fun skipToPrevious(initialTime: Double?) = runBlockingOnMain {
    player.previous()

    if (initialTime != null && initialTime >= 0) {
      player.seekTo((initialTime * 1000).toLong(), TimeUnit.MILLISECONDS)
    }
  }

  override fun reset() = runBlockingOnMain {
    player.stop()
    delay(300) // Allow playback to stop
    player.clear()
  }

  override fun play() = runBlockingOnMain { player.play() }

  override fun pause() = runBlockingOnMain { player.pause() }

  override fun togglePlayback() = runBlockingOnMain { player.togglePlayback() }

  override fun stop() = runBlockingOnMain { player.stop() }

  override fun seekTo(seconds: Double) = runBlockingOnMain {
    player.seekTo((seconds * 1000).toLong(), TimeUnit.MILLISECONDS)
  }

  override fun seekBy(offset: Double) = runBlockingOnMain {
    player.seekBy((offset * 1000).toLong(), TimeUnit.MILLISECONDS)
  }

  override fun retry() = runBlockingOnMain { player.prepare() }

  override fun setVolume(volume: Double) = runBlockingOnMain { player.volume = volume.toFloat() }

  override fun getVolume(): Double = runBlockingOnMain { player.volume.toDouble() }

  override fun setRate(rate: Double) = runBlockingOnMain { player.playbackSpeed = rate.toFloat() }

  override fun getRate(): Double = runBlockingOnMain { player.playbackSpeed.toDouble() }

  override fun setPlayWhenReady(playWhenReady: Boolean) = runBlockingOnMain {
    player.playWhenReady = playWhenReady
  }

  override fun getPlayWhenReady(): Boolean = runBlockingOnMain { player.playWhenReady }

  override fun getTrack(index: Double): WritableMap? = runBlockingOnMain {
    try {
      player.getTrack(index.toInt()).toBridge()
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  override fun getQueue(): WritableArray = runBlockingOnMain {
    Arguments.fromList(player.tracks.map { it.toBridge() })
  }

  override fun setQueue(data: ReadableArray?): Unit = runBlockingOnMain {
    data?.let {
      player.clear()
      player.add(trackFactory.tracksFromBridge(data))
    }
  }

  override fun getActiveTrackIndex(): Double? = runBlockingOnMain {
    player.currentIndex?.toDouble()
  }

  override fun getActiveTrack(): WritableMap? = runBlockingOnMain {
    player.currentTrack?.toBridge()
  }

  override fun getProgress(): WritableMap = runBlockingOnMain {
    Arguments.createMap().let {
      it.putDouble("duration", player.duration.toSeconds())
      it.putDouble("position", player.position.toSeconds())
      it.putDouble("buffered", player.bufferedPosition.toSeconds())
      it
    }
  }

  override fun getPlaybackState(): WritableMap = runBlockingOnMain {
    player.getPlaybackState().toBridge()
  }

  override fun getPlayingState(): WritableMap = runBlockingOnMain {
    player.getPlayingState().toBridge()
  }

  override fun getPlaybackError(): WritableMap? = runBlockingOnMain {
    player.playbackError?.toBridge()
  }

  override fun acquireWakeLock() = runBlockingOnMain { service.acquireWakeLock() }

  override fun abandonWakeLock() = runBlockingOnMain { service.abandonWakeLock() }

  // Bridgeless interop layer tries to pass the `Job` from `scope.launch` to the JS side
  // which causes an exception. We can work around this using a wrapper.
  private fun launchInScope(block: suspend () -> Unit) {
    mainScope.launch { block() }
  }

  private fun <T> runBlockingOnMain(block: suspend () -> T): T {
    return runBlocking(mainScope.coroutineContext) { block() }
  }

  private val service: TrackPlayerService
    get() = connectedService ?: throw Exception("Player not initialized")

  private val player
    get() = service.player

  public val callbacks =
    object : TrackPlayerCallbacks {
      override fun onPlaybackState(state: PlaybackState) {
        emitOnPlaybackState(state.toBridge())
      }

      override fun onPlaybackActiveTrackChanged(event: PlaybackActiveTrackChangedEvent) {
        emitOnPlaybackActiveTrackChanged(event.toBridge())
      }

      override fun onPlaybackProgressUpdated(event: PlaybackProgressUpdatedEvent) {
        emitOnPlaybackProgressUpdated(event.toBridge())
      }

      override fun onPlaybackPlayWhenReadyChanged(event: PlaybackPlayWhenReadyChangedEvent) {
        emitOnPlaybackPlayWhenReadyChanged(event.toBridge())
      }

      override fun onPlaybackPlayingState(event: PlaybackPlayingStateEvent) {
        emitOnPlaybackPlayingState(event.toBridge())
      }

      override fun onPlaybackQueueEnded(event: PlaybackQueueEndedEvent) {
        emitOnPlaybackQueueEnded(event.toBridge())
      }

      override fun onPlaybackRepeatModeChanged(event: PlaybackRepeatModeChangedEvent) {
        emitOnPlaybackRepeatModeChanged(event.toBridge())
      }

      override fun onPlaybackError(event: PlaybackErrorEvent) {
        emitOnPlaybackError(event.toBridge())
      }

      override fun onMetadataCommonReceived(metadata: WritableMap) {
        emitOnMetadataCommonReceived(Arguments.createMap().apply { putMap("metadata", metadata) })
      }

      override fun onMetadataTimedReceived(metadata: Metadata) {
        emitOnMetadataTimedReceived(
          Arguments.createMap().let {
            it.putArray(
              "metadata",
              Arguments.createArray().apply {
                MetadataAdapter.Companion.fromMetadata(metadata).forEach { item -> pushMap(item) }
              },
            )
            it
          }
        )
      }

      override fun onPlaybackMetadata(metadata: PlaybackMetadata?) {
        metadata?.let {
          emitOnPlaybackMetadata(
            Arguments.createMap().apply {
              putString("source", it.source)
              putString("title", it.title)
              putString("url", it.url)
              putString("artist", it.artist)
              putString("album", it.album)
              putString("date", it.date)
              putString("genre", it.genre)
            }
          )
        }
      }

      override fun onRemotePlay() {
        emitOnRemotePlay(Arguments.createMap())
      }

      override fun onRemotePause() {
        emitOnRemotePause(Arguments.createMap())
      }

      override fun onRemoteStop() {
        emitOnRemoteStop(Arguments.createMap())
      }

      override fun onRemoteNext() {
        Timber.d("onRemoteNext called - emitting event to JavaScript")
        emitOnRemoteNext(Arguments.createMap())
      }

      override fun onRemotePrevious() {
        Timber.d("onRemotePrevious called - emitting event to JavaScript")
        emitOnRemotePrevious(Arguments.createMap())
      }

      override fun onRemoteJumpForward(event: RemoteJumpForwardEvent) {
        Timber.d("onRemoteJumpForward called - emitting event to JavaScript")
        emitOnRemoteJumpForward(event.toBridge())
      }

      override fun onRemoteJumpBackward(event: RemoteJumpBackwardEvent) {
        Timber.d("onRemoteJumpBackward called - emitting event to JavaScript")
        emitOnRemoteJumpBackward(event.toBridge())
      }

      override fun onRemoteSeek(event: RemoteSeekEvent) {
        emitOnRemoteSeek(event.toBridge())
      }

      override fun onRemoteSetRating(event: RemoteSetRatingEvent) {
        emitOnRemoteSetRating(event.toBridge())
      }


      override fun onOptionsChanged(options: PlayerUpdateOptions) {
        emitOnOptionsChanged(options.toBridge())
      }

      // Media browser callbacks
      override fun onGetChildrenRequest(requestId: String, parentId: String, page: Int, pageSize: Int) {
        emitGetChildrenRequest(requestId, parentId, page, pageSize)
      }

      override fun onGetItemRequest(requestId: String, mediaId: String) {
        emitGetItemRequest(requestId, mediaId)
      }

      override fun onSearchRequest(requestId: String, query: String, extras: Map<String, Any>?) {
        val extrasMap = extras?.let { map ->
          Arguments.createMap().apply {
            map.forEach { (key, value) ->
              when (value) {
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Double -> putDouble(key, value)
                is Boolean -> putBoolean(key, value)
                // Add other types as needed
              }
            }
          }
        }
        emitSearchResultRequest(requestId, query, extrasMap, 0, 100)
      }
    }

  // Android Auto callback resolution methods
  override fun resolveGetItemRequest(id: String, item: ReadableMap) = runBlockingOnMain {
    val track = trackFactory.fromBridge(item)
    service.resolveGetItemRequest(id, track.toMediaItem())
  }

  override fun resolveGetChildrenRequest(
    requestId: String,
    items: ReadableArray,
    totalChildrenCount: Double,
  ) = runBlockingOnMain {
    Timber.d("resolveGetChildrenRequest called: requestId=$requestId, itemCount=${items.size()}")
    val tracks = trackFactory.tracksFromBridge(items)
    val mediaItems = tracks.map { it.toMediaItem() }
    Timber.d("Sending MediaItem extras: ${mediaItems.first().mediaMetadata.extras?.keySet()}")
    Timber.d("Resolving with ${mediaItems.size} items for requestId=$requestId")
    service.resolveGetChildrenRequest(requestId, mediaItems, totalChildrenCount.toInt())
    Timber.d("resolveGetChildrenRequest completed for requestId=$requestId")
  }

  override fun resolveSearchResultRequest(
    requestId: String,
    items: ReadableArray,
    totalMatchesCount: Double,
  ) = runBlockingOnMain {
    val tracks = trackFactory.tracksFromBridge(items)
    val mediaItems = tracks.map { it.toMediaItem() }
    service.resolveSearchRequest(requestId, mediaItems, totalMatchesCount.toInt())
  }

  // Android Auto event emission methods (called by TrackPlayerService)
  fun emitGetItemRequest(requestId: String, mediaId: String) {
    synchronized(bufferLock) {
      if (!mediaBrowserReady) {
        if (eventBuffer.size < maxBufferSize) {
          eventBuffer.add(BufferedEvent.GetItem(requestId, mediaId))
          Timber.d("Buffered GetItem event: requestId=$requestId, mediaId=$mediaId")
        } else {
          Timber.w("Event buffer full, dropping GetItem event: requestId=$requestId")
        }
        return
      }
    }

    Arguments.createMap()
      .apply {
        putString("requestId", requestId)
        putString("id", mediaId)
      }
      .let { eventData -> emitOnGetItemRequest(eventData) }
  }

  fun emitGetChildrenRequest(requestId: String, parentId: String, page: Int, pageSize: Int) {
    synchronized(bufferLock) {
      if (!mediaBrowserReady) {
        if (eventBuffer.size < maxBufferSize) {
          eventBuffer.add(BufferedEvent.GetChildren(requestId, parentId, page, pageSize))
          Timber.d("Buffered GetChildren event: requestId=$requestId, parentId=$parentId")
        } else {
          Timber.w("Event buffer full, dropping GetChildren event: requestId=$requestId")
        }
        return
      }
    }

    Arguments.createMap()
      .apply {
        putString("requestId", requestId)
        putString("id", parentId)
        putInt("page", page)
        putInt("pageSize", pageSize)
      }
      .let { eventData -> emitOnGetChildrenRequest(eventData) }
  }

  fun emitSearchResultRequest(
    requestId: String,
    query: String,
    extras: ReadableMap?,
    page: Int,
    pageSize: Int,
  ) {
    synchronized(bufferLock) {
      if (!mediaBrowserReady) {
        if (eventBuffer.size < maxBufferSize) {
          eventBuffer.add(BufferedEvent.SearchResults(requestId, query, extras, page, pageSize))
          Timber.d("Buffered SearchResults event: requestId=$requestId, query=$query")
        } else {
          Timber.w("Event buffer full, dropping SearchResults event: requestId=$requestId")
        }
        return
      }
    }

    Arguments.createMap()
      .apply {
        putString("requestId", requestId)
        putString("query", query)
        extras?.let { putMap("extras", it) }
        putInt("page", page)
        putInt("pageSize", pageSize)
      }
      .let { eventData -> emitOnGetSearchResultRequest(eventData) }
  }

  override fun setMediaBrowserReady() = runBlockingOnMain {
    synchronized(bufferLock) {
      mediaBrowserReady = true
      val bufferedEvents = eventBuffer.toList()
      eventBuffer.clear()

      Timber.d(
        "Media browser ready signal received, flushing ${bufferedEvents.size} buffered events"
      )

      // Emit all buffered events
      bufferedEvents.forEach { event ->
        when (event) {
          is BufferedEvent.GetItem -> {
            Arguments.createMap()
              .apply {
                putString("requestId", event.requestId)
                putString("id", event.mediaId)
              }
              .let { eventData -> emitOnGetItemRequest(eventData) }
          }

          is BufferedEvent.GetChildren -> {
            Arguments.createMap()
              .apply {
                putString("requestId", event.requestId)
                putString("id", event.parentId)
                putInt("page", event.page)
                putInt("pageSize", event.pageSize)
              }
              .let { eventData -> emitOnGetChildrenRequest(eventData) }
          }

          is BufferedEvent.SearchResults -> {
            Arguments.createMap()
              .apply {
                putString("requestId", event.requestId)
                putString("query", event.query)
                event.extras?.let { putMap("extras", it) }
                putInt("page", event.page)
                putInt("pageSize", event.pageSize)
              }
              .let { eventData -> emitOnGetSearchResultRequest(eventData) }
          }
        }
      }
    }
  }
}
