package com.audiobrowser

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
import com.margelo.nitro.NitroModules
import com.doublesymmetry.trackplayer.TrackPlayerService
import com.doublesymmetry.trackplayer.TrackPlayerCallbacks
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
import com.doublesymmetry.trackplayer.model.PlayerSetupOptions
import com.doublesymmetry.trackplayer.model.PlayerUpdateOptions
import com.doublesymmetry.trackplayer.model.Track
import com.doublesymmetry.trackplayer.model.TrackFactory
import com.doublesymmetry.trackplayer.option.PlayerRepeatMode
import com.facebook.react.bridge.WritableMap
import com.google.common.util.concurrent.ListenableFuture
import com.margelo.nitro.audiobrowser.HybridAudioBrowserSpec
import com.margelo.nitro.audiobrowser.Track as NitroTrack
import com.margelo.nitro.audiobrowser.PlaybackError as NitroPlaybackError
import com.margelo.nitro.audiobrowser.PlayerOptions as NitroPlayerOptions
import com.margelo.nitro.audiobrowser.PlayingState as NitroPlayingState
import com.margelo.nitro.audiobrowser.Progress as NitroProgress
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.PlaybackActiveTrackChangedEvent as NitroPlaybackActiveTrackChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackErrorEvent as NitroPlaybackErrorEvent
import com.margelo.nitro.audiobrowser.PlaybackPlayWhenReadyChangedEvent as NitroPlaybackPlayWhenReadyChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackProgressUpdatedEvent as NitroPlaybackProgressUpdatedEvent
import com.margelo.nitro.audiobrowser.PlaybackQueueEndedEvent as NitroPlaybackQueueEndedEvent
import com.margelo.nitro.audiobrowser.RepeatModeChangedEvent as NitroRepeatModeChangedEvent
import com.margelo.nitro.audiobrowser.RepeatMode as NitroRepeatMode
import com.margelo.nitro.core.Promise
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import android.os.Handler
import android.os.Looper
import com.margelo.nitro.audiobrowser.AudioCommonMetadataReceivedEvent
import com.margelo.nitro.audiobrowser.AudioMetadataReceivedEvent
import com.margelo.nitro.audiobrowser.Options
import com.margelo.nitro.audiobrowser.RemotePlayIdEvent
import com.margelo.nitro.audiobrowser.RemotePlaySearchEvent
import com.margelo.nitro.audiobrowser.RemoteSkipEvent
import com.doublesymmetry.trackplayer.model.CommonMetadata
import com.doublesymmetry.trackplayer.model.TimedMetadata
import com.margelo.nitro.audiobrowser.AudioMetadata
import com.margelo.nitro.audiobrowser.UpdateOptions

@Keep
@DoNotStrip
class AudioBrowser : HybridAudioBrowserSpec(), ServiceConnection {

    private lateinit var browser: MediaBrowser
    private var mediaBrowserFuture: ListenableFuture<MediaBrowser>? = null
    private var setupOptions = PlayerSetupOptions()
    private val mainScope = MainScope()
    private var connectedService: TrackPlayerService? = null
    private val context = NitroModules.applicationContext
        ?: throw IllegalStateException("NitroModules.applicationContext is null")

    @SuppressLint("RestrictedApi")
    private val trackFactory =
        TrackFactory(context) { connectedService?.player?.ratingType ?: RatingCompat.RATING_NONE }

    // MARK: callbacks
    override var onPlaybackStateChanged: (data: PlaybackState) -> Unit = { }
    override var onRemoteBookmark: () -> Unit = { }
    override var onRemoteDislike: () -> Unit = { }
    override var onRemoteJumpBackward: (com.margelo.nitro.audiobrowser.RemoteJumpBackwardEvent) -> Unit =
        { }
    override var onRemoteJumpForward: (com.margelo.nitro.audiobrowser.RemoteJumpForwardEvent) -> Unit =
        { }
    override var onRemoteLike: () -> Unit = { }
    override var onRemoteNext: () -> Unit = { }
    override var onRemotePause: () -> Unit = { }
    override var onMetadataChapterReceived: (AudioMetadataReceivedEvent) -> Unit = { }
    override var onMetadataCommonReceived: (AudioCommonMetadataReceivedEvent) -> Unit = { }
    override var onMetadataTimedReceived: (AudioMetadataReceivedEvent) -> Unit = { }
    override var onPlaybackMetadata: (com.margelo.nitro.audiobrowser.PlaybackMetadata) -> Unit = { }
    override var onPlaybackActiveTrackChanged: (data: NitroPlaybackActiveTrackChangedEvent) -> Unit =
        { }
    override var onPlaybackError: (data: NitroPlaybackErrorEvent) -> Unit = { }
    override var onPlaybackPlayWhenReadyChanged: (data: NitroPlaybackPlayWhenReadyChangedEvent) -> Unit =
        { }
    override var onPlaybackPlayingState: (data: NitroPlayingState) -> Unit = { }
    override var onPlaybackProgressUpdated: (data: NitroPlaybackProgressUpdatedEvent) -> Unit = { }
    override var onPlaybackQueueEnded: (data: NitroPlaybackQueueEndedEvent) -> Unit = { }
    override var onPlaybackRepeatModeChanged: (data: NitroRepeatModeChangedEvent) -> Unit = { }
    override var onRemotePlay: (() -> Unit) = { }
    override var onRemotePlayId: (RemotePlayIdEvent) -> Unit = { }
    override var onRemotePlaySearch: (RemotePlaySearchEvent) -> Unit = { }
    override var onRemotePrevious: () -> Unit = { }
    override var onRemoteSeek: (com.margelo.nitro.audiobrowser.RemoteSeekEvent) -> Unit = { }
    override var onRemoteSetRating: (com.margelo.nitro.audiobrowser.RemoteSetRatingEvent) -> Unit =
        { }
    override var onRemoteSkip: (RemoteSkipEvent) -> Unit = { }
    override var onRemoteStop: () -> Unit = { }
    override var onOptionsChanged: (Options) -> Unit = { }

    // MARK: handlers
    override var handleRemoteBookmark: (() -> Unit)? = null
    override var handleRemoteDislike: (() -> Unit)? = null
    override var handleRemoteJumpBackward: ((com.margelo.nitro.audiobrowser.RemoteJumpBackwardEvent) -> Unit)? =
        null
    override var handleRemoteJumpForward: ((com.margelo.nitro.audiobrowser.RemoteJumpForwardEvent) -> Unit)? =
        null
    override var handleRemoteLike: (() -> Unit)? = null
    override var handleRemoteNext: (() -> Unit)? = null
    override var handleRemotePause: (() -> Unit)? = null
    override var handleRemotePlay: (() -> Unit)? = null
    override var handleRemotePlayId: ((RemotePlayIdEvent) -> Unit)? = null
    override var handleRemotePlaySearch: ((RemotePlaySearchEvent) -> Unit)? = null
    override var handleRemotePrevious: (() -> Unit)? = null
    override var handleRemoteSeek: ((com.margelo.nitro.audiobrowser.RemoteSeekEvent) -> Unit)? =
        null
    override var handleRemoteSetRating: ((com.margelo.nitro.audiobrowser.RemoteSetRatingEvent) -> Unit)? =
        null
    override var handleRemoteSkip: (() -> Unit)? = null
    override var handleRemoteStop: (() -> Unit)? = null

    init {
        // Auto-bind to service if it's already running
        launchInScope {
            try {
                Timber.d("Attempting to auto-bind to existing TrackPlayerService from AudioBrowserModule")
                val intent = Intent(context, TrackPlayerService::class.java)
                val bound = context.bindService(intent, this@AudioBrowser, Context.BIND_AUTO_CREATE)
                Timber.d("Auto-bind result: $bound")

                if (!bound) {
                    Timber.w("Failed to bind to TrackPlayerService - service may not be running")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to auto-bind to TrackPlayerService during initialization")
            }
        }
    }

    override fun setupPlayer(options: NitroPlayerOptions): Promise<Unit> {
        return Promise.async(mainScope) {
            // Convert Nitro PlayerOptions to TrackPlayer setup options
            setupOptions.updateFromNitro(options)

            connectedService?.let {
                it.player.setup(setupOptions)
                return@async
            }

            // Service not connected yet, bind to service
            suspendCoroutine<Unit> { continuation ->
                Timber.d("Binding to TrackPlayerService")
                val bound = context.bindService(
                    Intent(context, TrackPlayerService::class.java),
                    this@AudioBrowser,
                    Context.BIND_AUTO_CREATE
                )

                if (!bound) {
                    continuation.resumeWithException(RuntimeException("Failed to bind to TrackPlayerService"))
                } else {
                    // Service will resolve the promise in onServiceConnected
                    setupPromise = { continuation.resume(Unit) }
                }
            }
        }
    }

    override fun updateOptions(options: UpdateOptions) {
        val currentOptions = player.getOptions()
        val updatedOptions = currentOptions.copy()
        updatedOptions.updateFromNitro(options)
        player.applyOptions(updatedOptions)
    }

    override fun getOptions(): UpdateOptions {
        return player.getOptions().toNitro()
    }

    private var setupPromise: ((Unit) -> Unit)? = null

    override fun load(track: NitroTrack): Unit = runBlockingOnMain {
        player.load(Track.fromNitro(track, context))
    }

    override fun reset() = runBlockingOnMain {
        player.stop()
        delay(300) // Allow playback to stop
        player.clear()
    }

    override fun play() = runBlockingOnMain {
        player.play()
    }

    override fun pause() = runBlockingOnMain {
        player.pause()
    }

    override fun togglePlayback() = runBlockingOnMain {
        player.togglePlayback()
    }

    override fun stop() = runBlockingOnMain {
        player.stop()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) = runBlockingOnMain {
        player.playWhenReady = playWhenReady
    }

    override fun getPlayWhenReady(): Boolean = runBlockingOnMain {
        player.playWhenReady
    }

    override fun seekTo(position: Double) = runBlockingOnMain {
        player.seekTo((position * 1000).toLong(), TimeUnit.MILLISECONDS)
    }

    override fun seekBy(offset: Double) = runBlockingOnMain {
        player.seekBy((offset * 1000).toLong(), TimeUnit.MILLISECONDS)
    }

    override fun setVolume(level: Double) = runBlockingOnMain {
        player.volume = level.toFloat()
    }

    override fun getVolume(): Double = runBlockingOnMain {
        player.volume.toDouble()
    }

    override fun setRate(rate: Double) = runBlockingOnMain {
        player.playbackSpeed = rate.toFloat()
    }

    override fun getRate(): Double = runBlockingOnMain {
        player.playbackSpeed.toDouble()
    }

    override fun getProgress(): NitroProgress = runBlockingOnMain {
        NitroProgress(
            duration = player.duration.toSeconds(),
            position = player.position.toSeconds(),
            buffered = player.bufferedPosition.toSeconds()
        )
    }

    override fun getPlaybackState(): PlaybackState = runBlockingOnMain {
        player.getPlaybackState().toNitro()
    }

    override fun getPlayingState(): NitroPlayingState = runBlockingOnMain {
        player.getPlayingState().toNitro()
    }

    override fun getRepeatMode(): NitroRepeatMode = runBlockingOnMain {
        player.repeatMode.toNitro()
    }

    override fun setRepeatMode(mode: NitroRepeatMode) = runBlockingOnMain {
        player.repeatMode = PlayerRepeatMode.fromNitro(mode)
    }

    override fun getPlaybackError(): NitroPlaybackError? = runBlockingOnMain {
        player.playbackError?.toNitro()
    }

    override fun retry() = runBlockingOnMain {
        player.prepare()
    }

    override fun add(
        tracks: Array<NitroTrack>,
        insertBeforeIndex: Double?
    ) = runBlockingOnMain {
        val inputIndex = insertBeforeIndex?.toInt() ?: -1
        val internalTracks = tracks.map { Track.fromNitro(it, context) }
        player.add(internalTracks, inputIndex)
    }

    override fun move(fromIndex: Double, toIndex: Double) = runBlockingOnMain {
        player.move(fromIndex.toInt(), toIndex.toInt())
    }

    override fun remove(indexes: DoubleArray) = runBlockingOnMain {
        val indexList = indexes.map { it.toInt() }
        player.remove(indexList)
    }

    override fun removeUpcomingTracks() = runBlockingOnMain {
        player.removeUpcomingTracks()
    }

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

    override fun setQueue(tracks: Array<NitroTrack>) = runBlockingOnMain {
        player.clear()
        val internalTracks = tracks.map { Track.fromNitro(it, context) }
        player.add(internalTracks)
    }

    override fun getQueue(): Array<NitroTrack> = runBlockingOnMain {
        player.tracks.map { it.toNitro() }.toTypedArray()
    }

    override fun getTrack(index: Double): NitroTrack? = runBlockingOnMain {
        try {
            player.getTrack(index.toInt()).toNitro()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override fun getActiveTrackIndex(): Double? = runBlockingOnMain {
        player.currentIndex?.toDouble()
    }

    override fun getActiveTrack(): NitroTrack? = runBlockingOnMain {
        player.currentTrack?.toNitro()
    }

    override fun acquireWakeLock() = runBlockingOnMain {
        service.acquireWakeLock()
    }

    override fun abandonWakeLock() = runBlockingOnMain {
        service.abandonWakeLock()
    }

    override fun onServiceConnected(name: ComponentName, serviceBinder: IBinder) {
        launchInScope {
            connectedService = (serviceBinder as TrackPlayerService.LocalBinder).service.apply {
                player.setCallbacks(callbacks)
                player.setup(setupOptions)
            }

            val sessionToken =
                SessionToken(context, ComponentName(context, TrackPlayerService::class.java))
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

    private val service: TrackPlayerService
        get() = connectedService ?: throw Exception("Player not initialized")

    private val player
        get() = service.player


    val callbacks =
        object : TrackPlayerCallbacks {
            override fun onPlaybackState(state: com.doublesymmetry.trackplayer.model.PlaybackState) {
                onPlaybackStateChanged(state.toNitro())
            }

            override fun onPlaybackActiveTrackChanged(event: PlaybackActiveTrackChangedEvent) {
                onPlaybackActiveTrackChanged(event.toNitro())
            }

            override fun onPlaybackProgressUpdated(event: PlaybackProgressUpdatedEvent) {
                onPlaybackProgressUpdated(event.toNitro())
            }

            override fun onPlaybackPlayWhenReadyChanged(event: PlaybackPlayWhenReadyChangedEvent) {
                onPlaybackPlayWhenReadyChanged(event.toNitro())
            }

            override fun onPlaybackPlayingState(event: PlaybackPlayingStateEvent) {
                onPlaybackPlayingState(event.toNitro())
            }

            override fun onPlaybackQueueEnded(event: PlaybackQueueEndedEvent) {
                onPlaybackQueueEnded(event.toNitro())
            }

            override fun onPlaybackRepeatModeChanged(event: PlaybackRepeatModeChangedEvent) {
                onPlaybackRepeatModeChanged(event.toNitro())
            }

            override fun onPlaybackError(event: PlaybackErrorEvent) {
                onPlaybackError(event.toNitro())
            }

            override fun onMetadataCommonReceived(metadata: AudioMetadata) {
                onMetadataCommonReceived(AudioCommonMetadataReceivedEvent(metadata))

            }

            override fun onMetadataTimedReceived(metadata: TimedMetadata) {
                onMetadataTimedReceived(metadata.toNitro())
            }

            override fun onPlaybackMetadata(metadata: PlaybackMetadata) {
                onPlaybackMetadata(metadata.toNitro())
            }

            override fun handleRemotePlay(): Boolean {
                val handled = handleRemotePlay?.let {
                    it.invoke()
                    true
                } ?: false

                // Defer notification until after play operation completes
                Handler(Looper.getMainLooper()).post {
                    onRemotePlay()
                }

                return handled
            }

            override fun handleRemotePause(): Boolean {
                val handled = handleRemotePause?.let {
                    it.invoke()
                    true
                } ?: false

                // Defer notification until after pause operation completes
                Handler(Looper.getMainLooper()).post {
                    onRemotePause()
                }

                return handled
            }

            override fun handleRemoteStop(): Boolean {
                val handled = handleRemoteStop?.let {
                    it.invoke()
                    true
                } ?: false

                // Defer notification until after stop operation completes
                Handler(Looper.getMainLooper()).post {
                    onRemoteStop()
                }

                return handled
            }

            override fun handleRemoteNext(): Boolean {
                val handled = handleRemoteNext?.let {
                    it.invoke()
                    true
                } ?: false

                // Defer notification until after next operation completes
                Handler(Looper.getMainLooper()).post {
                    onRemoteNext()
                }

                return handled
            }

            override fun handleRemotePrevious(): Boolean {
                val handled = handleRemotePrevious?.let {
                    it.invoke()
                    true
                } ?: false

                // Defer notification until after previous operation completes
                Handler(Looper.getMainLooper()).post {
                    onRemotePrevious()
                }

                return handled
            }

            override fun handleRemoteJumpForward(event: RemoteJumpForwardEvent): Boolean {
                val nitroEvent = event.toNitro()
                val handled = handleRemoteJumpForward?.let {
                    it.invoke(nitroEvent)
                    true
                } ?: false

                // Defer notification until after jump forward operation completes
                Handler(Looper.getMainLooper()).post {
                    onRemoteJumpForward(nitroEvent)
                }

                return handled
            }

            override fun handleRemoteJumpBackward(event: RemoteJumpBackwardEvent): Boolean {
                val nitroEvent = event.toNitro()
                val handled = handleRemoteJumpBackward?.let {
                    it.invoke(nitroEvent)
                    true
                } ?: false

                // Defer notification until after jump backward operation completes
                Handler(Looper.getMainLooper()).post {
                    onRemoteJumpBackward(nitroEvent)
                }

                return handled
            }

            override fun handleRemoteSeek(event: RemoteSeekEvent): Boolean {
                val nitroEvent = event.toNitro()
                val handled = handleRemoteSeek?.let {
                    it.invoke(nitroEvent)
                    true
                } ?: false

                // Defer notification until after seek operation completes
                Handler(Looper.getMainLooper()).post {
                    onRemoteSeek(nitroEvent)
                }

                return handled
            }

            override fun handleRemoteSetRating(event: RemoteSetRatingEvent): Boolean {
                val nitroEvent = event.toNitro()
                val handled = handleRemoteSetRating?.let {
                    it.invoke(nitroEvent)
                    true
                } ?: false

                // Defer notification until after set rating operation completes
                Handler(Looper.getMainLooper()).post {
                    onRemoteSetRating(nitroEvent)
                }

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
                pageSize: Int
            ) {
//                emitGetChildrenRequest(requestId, parentId, page, pageSize)
            }

            override fun onGetItemRequest(requestId: String, mediaId: String) {
//                emitGetItemRequest(requestId, mediaId)
            }

            override fun onSearchRequest(
                requestId: String,
                query: String,
                extras: Map<String, Any>?
            ) {
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
