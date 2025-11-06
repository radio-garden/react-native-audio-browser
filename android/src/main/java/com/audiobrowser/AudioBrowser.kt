package com.audiobrowser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.Keep
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.audiobrowser.extension.NumberExt.Companion.toSeconds
import com.audiobrowser.model.PlaybackMetadata
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.model.TimedMetadata
import com.facebook.proguard.annotations.DoNotStrip
import com.google.common.util.concurrent.ListenableFuture
import com.margelo.nitro.NitroModules
import com.margelo.nitro.audiobrowser.AudioCommonMetadataReceivedEvent
import com.margelo.nitro.audiobrowser.AudioMetadata
import com.margelo.nitro.audiobrowser.AudioMetadataReceivedEvent
import com.margelo.nitro.audiobrowser.HybridAudioBrowserSpec
import com.margelo.nitro.audiobrowser.NativeUpdateOptions
import com.margelo.nitro.audiobrowser.Options
import com.margelo.nitro.audiobrowser.PartialSetupPlayerOptions
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.PlaybackActiveTrackChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackErrorEvent
import com.margelo.nitro.audiobrowser.PlaybackPlayWhenReadyChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackProgressUpdatedEvent
import com.margelo.nitro.audiobrowser.PlaybackQueueEndedEvent
import com.margelo.nitro.audiobrowser.PlayingState
import com.margelo.nitro.audiobrowser.Progress
import com.margelo.nitro.audiobrowser.RemoteJumpBackwardEvent
import com.margelo.nitro.audiobrowser.RemoteJumpForwardEvent
import com.margelo.nitro.audiobrowser.RemotePlayIdEvent
import com.margelo.nitro.audiobrowser.RemotePlaySearchEvent
import com.margelo.nitro.audiobrowser.RemoteSeekEvent
import com.margelo.nitro.audiobrowser.RemoteSetRatingEvent
import com.margelo.nitro.audiobrowser.RemoteSkipEvent
import com.margelo.nitro.audiobrowser.RepeatMode
import com.margelo.nitro.audiobrowser.RepeatModeChangedEvent
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.UpdateOptions
import com.margelo.nitro.core.Promise
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

@Keep
@DoNotStrip
class AudioBrowser : HybridAudioBrowserSpec(), ServiceConnection {
  private lateinit var browser: MediaBrowser
  private var updateOptions: PlayerUpdateOptions = PlayerUpdateOptions()
  private var mediaBrowserFuture: ListenableFuture<MediaBrowser>? = null
  private var setupOptions = PlayerSetupOptions()
  private val mainScope = MainScope()
  private var connectedService: Service? = null
  private val context =
    NitroModules.applicationContext
      ?: throw IllegalStateException("NitroModules.applicationContext is null")

  // MARK: callbacks
  override var onPlaybackChanged: (data: Playback) -> Unit = {}
  override var onRemoteBookmark: () -> Unit = {}
  override var onRemoteDislike: () -> Unit = {}
  override var onRemoteJumpBackward: (RemoteJumpBackwardEvent) -> Unit = {}
  override var onRemoteJumpForward: (RemoteJumpForwardEvent) -> Unit = {}
  override var onRemoteLike: () -> Unit = {}
  override var onRemoteNext: () -> Unit = {}
  override var onRemotePause: () -> Unit = {}
  override var onMetadataChapterReceived: (AudioMetadataReceivedEvent) -> Unit = {}
  override var onMetadataCommonReceived: (AudioCommonMetadataReceivedEvent) -> Unit = {}
  override var onMetadataTimedReceived: (AudioMetadataReceivedEvent) -> Unit = {}
  override var onPlaybackMetadata: (com.margelo.nitro.audiobrowser.PlaybackMetadata) -> Unit = {}
  override var onPlaybackActiveTrackChanged: (data: PlaybackActiveTrackChangedEvent) -> Unit = {}
  override var onPlaybackError: (data: PlaybackErrorEvent) -> Unit = {}
  override var onPlaybackPlayWhenReadyChanged: (data: PlaybackPlayWhenReadyChangedEvent) -> Unit =
    {}
  override var onPlaybackPlayingState: (data: PlayingState) -> Unit = {}
  override var onPlaybackProgressUpdated: (data: PlaybackProgressUpdatedEvent) -> Unit = {}
  override var onPlaybackQueueEnded: (data: PlaybackQueueEndedEvent) -> Unit = {}
  override var onPlaybackRepeatModeChanged: (data: RepeatModeChangedEvent) -> Unit = {}
  override var onRemotePlay: (() -> Unit) = {}
  override var onRemotePlayId: (RemotePlayIdEvent) -> Unit = {}
  override var onRemotePlaySearch: (RemotePlaySearchEvent) -> Unit = {}
  override var onRemotePrevious: () -> Unit = {}
  override var onRemoteSeek: (RemoteSeekEvent) -> Unit = {}
  override var onRemoteSetRating: (RemoteSetRatingEvent) -> Unit = {}
  override var onRemoteSkip: (RemoteSkipEvent) -> Unit = {}
  override var onRemoteStop: () -> Unit = {}
  override var onOptionsChanged: (Options) -> Unit = {}

  // MARK: handlers
  override var handleRemoteBookmark: (() -> Unit)? = null
  override var handleRemoteDislike: (() -> Unit)? = null
  override var handleRemoteJumpBackward: ((RemoteJumpBackwardEvent) -> Unit)? = null
  override var handleRemoteJumpForward: ((RemoteJumpForwardEvent) -> Unit)? = null
  override var handleRemoteLike: (() -> Unit)? = null
  override var handleRemoteNext: (() -> Unit)? = null
  override var handleRemotePause: (() -> Unit)? = null
  override var handleRemotePlay: (() -> Unit)? = null
  override var handleRemotePlayId: ((RemotePlayIdEvent) -> Unit)? = null
  override var handleRemotePlaySearch: ((RemotePlaySearchEvent) -> Unit)? = null
  override var handleRemotePrevious: (() -> Unit)? = null
  override var handleRemoteSeek: ((RemoteSeekEvent) -> Unit)? = null
  override var handleRemoteSetRating: ((RemoteSetRatingEvent) -> Unit)? = null
  override var handleRemoteSkip: (() -> Unit)? = null
  override var handleRemoteStop: (() -> Unit)? = null

  init {
    // Auto-bind to service if it's already running
    launchInScope {
      try {
        Timber.d("Attempting to auto-bind to existing AudioBrowserService from AudioBrowserModule")
        val intent = Intent(context, Service::class.java)
        val bound = context.bindService(intent, this@AudioBrowser, Context.BIND_AUTO_CREATE)
        Timber.d("Auto-bind result: $bound")

        if (!bound) {
          Timber.w("Failed to bind to AudioBrowserService - service may not be running")
        }
      } catch (e: Exception) {
        Timber.e(e, "Failed to auto-bind to AudioBrowserService during initialization")
      }
    }
  }

  override fun setupPlayer(options: PartialSetupPlayerOptions): Promise<Unit> {
    return Promise.async(mainScope) {
      setupOptions.update(options)

      connectedService?.let {
        it.player.setup(setupOptions)
        return@async
      }

      // Service not connected yet, bind to service
      suspendCoroutine<Unit> { continuation ->
        Timber.d("Binding to AudioBrowserService")
        val bound =
          context.bindService(
            Intent(context, Service::class.java),
            this@AudioBrowser,
            Context.BIND_AUTO_CREATE,
          )

        if (!bound) {
          continuation.resumeWithException(
            RuntimeException("Failed to bind to AudioBrowserService")
          )
        } else {
          // Service will resolve the promise in onServiceConnected
          setupPromise = { continuation.resume(Unit) }
        }
      }
    }
  }

  override fun updateOptions(options: NativeUpdateOptions) {
    updateOptions.updateFromBridge(options)
    // Only update the options if the service is around
    connectedService?.let { player.applyOptions(updateOptions) }
  }

  override fun getOptions(): UpdateOptions {
    return player.getOptions().toNitro()
  }

  private var setupPromise: ((Unit) -> Unit)? = null

  override fun load(track: Track): Unit = runBlockingOnMain { player.load(track) }

  override fun reset() = runBlockingOnMain {
    player.stop()
    delay(300) // Allow playback to stop
    player.clear()
  }

  override fun play() = runBlockingOnMain { player.play() }

  override fun pause() = runBlockingOnMain { player.pause() }

  override fun togglePlayback() = runBlockingOnMain { player.togglePlayback() }

  override fun stop() = runBlockingOnMain { player.stop() }

  override fun setPlayWhenReady(playWhenReady: Boolean) = runBlockingOnMain {
    player.playWhenReady = playWhenReady
  }

  override fun getPlayWhenReady(): Boolean = runBlockingOnMain { player.playWhenReady }

  override fun seekTo(position: Double) = runBlockingOnMain {
    player.seekTo((position * 1000).toLong(), TimeUnit.MILLISECONDS)
  }

  override fun seekBy(offset: Double) = runBlockingOnMain {
    player.seekBy((offset * 1000).toLong(), TimeUnit.MILLISECONDS)
  }

  override fun setVolume(level: Double) = runBlockingOnMain { player.volume = level.toFloat() }

  override fun getVolume(): Double = runBlockingOnMain { player.volume.toDouble() }

  override fun setRate(rate: Double) = runBlockingOnMain { player.playbackSpeed = rate.toFloat() }

  override fun getRate(): Double = runBlockingOnMain { player.playbackSpeed.toDouble() }

  override fun getProgress(): Progress = runBlockingOnMain {
    Progress(
      duration = player.duration.toSeconds(),
      position = player.position.toSeconds(),
      buffered = player.bufferedPosition.toSeconds(),
    )
  }

  override fun getPlayback(): Playback = runBlockingOnMain { player.getPlayback() }

  override fun getPlayingState(): PlayingState = runBlockingOnMain { player.getPlayingState() }

  override fun getRepeatMode(): RepeatMode = runBlockingOnMain { player.repeatMode }

  override fun setRepeatMode(mode: RepeatMode) = runBlockingOnMain { player.repeatMode = mode }

  override fun getPlaybackError(): PlaybackError? = runBlockingOnMain { player.playbackError }

  override fun retry() = runBlockingOnMain { player.prepare() }

  override fun add(tracks: Array<Track>, insertBeforeIndex: Double?) = runBlockingOnMain {
    val inputIndex = insertBeforeIndex?.toInt() ?: -1
    player.add(tracks, inputIndex)
  }

  override fun move(fromIndex: Double, toIndex: Double) = runBlockingOnMain {
    player.move(fromIndex.toInt(), toIndex.toInt())
  }

  override fun remove(indexes: DoubleArray) = runBlockingOnMain {
    val indexList = indexes.map { it.toInt() }
    player.remove(indexList)
  }

  override fun removeUpcomingTracks() = runBlockingOnMain { player.removeUpcomingTracks() }

  override fun skip(index: Double, initialPosition: Double?) = runBlockingOnMain {
    player.skipTo(index.toInt())

    if (initialPosition != null && initialPosition >= 0) {
      player.seekTo((initialPosition * 1000).toLong(), TimeUnit.MILLISECONDS)
    }
  }

  override fun skipToNext(initialPosition: Double?) = runBlockingOnMain {
    player.next()

    if (initialPosition != null && initialPosition >= 0) {
      player.seekTo((initialPosition * 1000).toLong(), TimeUnit.MILLISECONDS)
    }
  }

  override fun skipToPrevious(initialPosition: Double?) = runBlockingOnMain {
    player.previous()

    if (initialPosition != null && initialPosition >= 0) {
      player.seekTo((initialPosition * 1000).toLong(), TimeUnit.MILLISECONDS)
    }
  }

  override fun setQueue(tracks: Array<Track>) = runBlockingOnMain {
    player.clear()
    player.add(tracks)
  }

  override fun getQueue(): Array<Track> = runBlockingOnMain { player.tracks }

  override fun getTrack(index: Double): Track? = runBlockingOnMain {
    try {
      player.getTrack(index.toInt())
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  override fun getActiveTrackIndex(): Double? = runBlockingOnMain {
    player.currentIndex?.toDouble()
  }

  override fun getActiveTrack(): Track? = runBlockingOnMain { player.currentTrack }

  override fun onServiceConnected(name: ComponentName, serviceBinder: IBinder) {
    launchInScope {
      connectedService =
        (serviceBinder as Service.LocalBinder).service.apply {
          player.setCallbacks(callbacks)
          player.applyOptions(updateOptions)
          player.setup(setupOptions)
        }

      val sessionToken = SessionToken(context, ComponentName(context, Service::class.java))
      mediaBrowserFuture = MediaBrowser.Builder(context, sessionToken).buildAsync()

      setupPromise?.invoke(Unit)
      setupPromise = null
    }
  }

  override fun onServiceDisconnected(name: ComponentName) {
    mainScope.coroutineContext.cancelChildren()
    mediaBrowserFuture = null
    connectedService = null
    Timber.d("AudioBrowserModule.onServiceDisconnected()")
  }

  private fun launchInScope(block: suspend () -> Unit) {
    mainScope.launch { block() }
  }

  private fun <T> runBlockingOnMain(block: suspend () -> T): T {
    return runBlocking(mainScope.coroutineContext) { block() }
  }

  private val service: Service
    get() = connectedService ?: throw Exception("Player not initialized")

  private val player
    get() = service.player

  val callbacks =
    object : Callbacks {
      override fun onPlaybackChanged(playback: Playback) {
        this@AudioBrowser.onPlaybackChanged(playback)
      }

      override fun onPlaybackActiveTrackChanged(event: PlaybackActiveTrackChangedEvent) {
        this@AudioBrowser.onPlaybackActiveTrackChanged(event)
      }

      override fun onPlaybackProgressUpdated(event: PlaybackProgressUpdatedEvent) {
        this@AudioBrowser.onPlaybackProgressUpdated(event)
      }

      override fun onPlaybackPlayWhenReadyChanged(event: PlaybackPlayWhenReadyChangedEvent) {
        this@AudioBrowser.onPlaybackPlayWhenReadyChanged(event)
      }

      override fun onPlaybackPlayingState(event: PlayingState) {
        this@AudioBrowser.onPlaybackPlayingState(event)
      }

      override fun onPlaybackQueueEnded(event: PlaybackQueueEndedEvent) {
        this@AudioBrowser.onPlaybackQueueEnded(event)
      }

      override fun onPlaybackRepeatModeChanged(event: RepeatMode) {
        this@AudioBrowser.onPlaybackRepeatModeChanged(RepeatModeChangedEvent(event))
      }

      override fun onPlaybackError(error: PlaybackError?) {
        this@AudioBrowser.onPlaybackError(PlaybackErrorEvent(error))
      }

      override fun onMetadataCommonReceived(metadata: AudioMetadata) {
        this@AudioBrowser.onMetadataCommonReceived(AudioCommonMetadataReceivedEvent(metadata))
      }

      override fun onMetadataTimedReceived(metadata: TimedMetadata) {
        this@AudioBrowser.onMetadataTimedReceived(metadata.toNitro())
      }

      override fun onPlaybackMetadata(metadata: PlaybackMetadata) {
        this@AudioBrowser.onPlaybackMetadata(metadata.toNitro())
      }

      override fun handleRemotePlay(): Boolean {
        val handled =
          this@AudioBrowser.handleRemotePlay?.let {
            it.invoke()
            true
          } ?: false

        // Defer notification until after play operation completes
        Handler(Looper.getMainLooper()).post { this@AudioBrowser.onRemotePlay() }

        return handled
      }

      override fun handleRemotePause(): Boolean {
        val handled =
          this@AudioBrowser.handleRemotePause?.let {
            it.invoke()
            true
          } ?: false

        // Defer notification until after pause operation completes
        Handler(Looper.getMainLooper()).post { this@AudioBrowser.onRemotePause() }

        return handled
      }

      override fun handleRemoteStop(): Boolean {
        val handled =
          this@AudioBrowser.handleRemoteStop?.let {
            it.invoke()
            true
          } ?: false

        // Defer notification until after stop operation completes
        Handler(Looper.getMainLooper()).post { this@AudioBrowser.onRemoteStop() }

        return handled
      }

      override fun handleRemoteNext(): Boolean {
        val handled =
          this@AudioBrowser.handleRemoteNext?.let {
            it.invoke()
            true
          } ?: false

        // Defer notification until after next operation completes
        Handler(Looper.getMainLooper()).post { this@AudioBrowser.onRemoteNext() }

        return handled
      }

      override fun handleRemotePrevious(): Boolean {
        val handled =
          this@AudioBrowser.handleRemotePrevious?.let {
            it.invoke()
            true
          } ?: false

        // Defer notification until after previous operation completes
        Handler(Looper.getMainLooper()).post { this@AudioBrowser.onRemotePrevious() }

        return handled
      }

      override fun handleRemoteJumpForward(event: RemoteJumpForwardEvent): Boolean {
        val handled =
          this@AudioBrowser.handleRemoteJumpForward?.let {
            it.invoke(event)
            true
          } ?: false

        // Defer notification until after jump forward operation completes
        Handler(Looper.getMainLooper()).post { this@AudioBrowser.onRemoteJumpForward(event) }

        return handled
      }

      override fun handleRemoteJumpBackward(event: RemoteJumpBackwardEvent): Boolean {
        val handled =
          this@AudioBrowser.handleRemoteJumpBackward?.let {
            it.invoke(event)
            true
          } ?: false

        // Defer notification until after jump backward operation completes
        Handler(Looper.getMainLooper()).post { this@AudioBrowser.onRemoteJumpBackward(event) }

        return handled
      }

      override fun handleRemoteSeek(event: RemoteSeekEvent): Boolean {
        val handled =
          this@AudioBrowser.handleRemoteSeek?.let {
            it.invoke(event)
            true
          } ?: false

        // Defer notification until after seek operation completes
        Handler(Looper.getMainLooper()).post { this@AudioBrowser.onRemoteSeek(event) }

        return handled
      }

      override fun handleRemoteSetRating(event: RemoteSetRatingEvent): Boolean {
        val handled =
          this@AudioBrowser.handleRemoteSetRating?.let {
            it.invoke(event)
            true
          } ?: false

        // Defer notification until after set rating operation completes
        Handler(Looper.getMainLooper()).post { this@AudioBrowser.onRemoteSetRating(event) }

        return handled
      }

      override fun onOptionsChanged(options: PlayerUpdateOptions) {
        //                emitOnOptionsChanged(options.toBridge())
      }

      // Media browser callbacks
      override fun onGetChildrenRequest(
        requestId: String,
        parentId: String,
        page: Int,
        pageSize: Int,
      ) {
        //                emitGetChildrenRequest(requestId, parentId, page, pageSize)
      }

      override fun onGetItemRequest(requestId: String, mediaId: String) {
        //                emitGetItemRequest(requestId, mediaId)
      }

      override fun onSearchRequest(requestId: String, query: String, extras: Map<String, Any>?) {
        //                val extrasMap = extras?.let { map ->
        //                    Arguments.createMap().apply {
        //                        map.forEach { (key, value) ->
        //                            when (value) {
        //                                is String -> putString(key, value)
        //                                is Int -> putInt(key, value)
        //                                is Double -> putDouble(key, value)
        //                                is Boolean -> putBoolean(key, value)
        //                                // Add other types as needed
        //                            }
        //                        }
        //                    }
        //                }
        //                emitSearchResultRequest(requestId, query, extrasMap, 0, 100)
      }
    }
}
