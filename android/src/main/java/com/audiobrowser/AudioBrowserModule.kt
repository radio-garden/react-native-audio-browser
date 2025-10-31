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
import com.doublesymmetry.trackplayer.model.PlaybackState
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
import com.margelo.nitro.audiobrowser.PlaybackState as NitroPlaybackState
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

@Keep
@DoNotStrip
class AudioBrowserModule: HybridAudioBrowserSpec(), ServiceConnection {
    
    private lateinit var browser: MediaBrowser
    private var mediaBrowserFuture: ListenableFuture<MediaBrowser>? = null
    private var setupOptions = PlayerSetupOptions()
    private val mainScope = MainScope()
    private var connectedService: TrackPlayerService? = null
    private val context = applicationContext ?: throw IllegalStateException("Application context not set")
    @SuppressLint("RestrictedApi")
    private val trackFactory = TrackFactory(context) { connectedService?.player?.ratingType ?: RatingCompat.RATING_NONE }

    init {
        // Auto-bind to service if it's already running
        launchInScope {
            try {
                Timber.d("Attempting to auto-bind to existing TrackPlayerService from AudioBrowserModule")
                val intent = Intent(context, TrackPlayerService::class.java)
                val bound = context.bindService(intent, this@AudioBrowserModule, Context.BIND_AUTO_CREATE)
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
        return Promise.async {
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
                    this@AudioBrowserModule,
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

    override fun getPlaybackState(): NitroPlaybackState = runBlockingOnMain {
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
            
            val sessionToken = SessionToken(context, ComponentName(context, TrackPlayerService::class.java))
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
    
    companion object {
        // Static context holder for Nitro modules
        @JvmStatic
        var applicationContext: Context? = null
            private set

        @JvmStatic
        fun setApplicationContext(context: Context) {
            applicationContext = context.applicationContext
        }
    }

    val callbacks =
        object : TrackPlayerCallbacks {
            override fun onPlaybackState(state: PlaybackState) {
//                emitOnPlaybackState(state.toBridge())
            }

            override fun onPlaybackActiveTrackChanged(event: PlaybackActiveTrackChangedEvent) {
//                emitOnPlaybackActiveTrackChanged(event.toBridge())
            }

            override fun onPlaybackProgressUpdated(event: PlaybackProgressUpdatedEvent) {
//                emitOnPlaybackProgressUpdated(event.toBridge())
            }

            override fun onPlaybackPlayWhenReadyChanged(event: PlaybackPlayWhenReadyChangedEvent) {
//                emitOnPlaybackPlayWhenReadyChanged(event.toBridge())
            }

            override fun onPlaybackPlayingState(event: PlaybackPlayingStateEvent) {
//                emitOnPlaybackPlayingState(event.toBridge())
            }

            override fun onPlaybackQueueEnded(event: PlaybackQueueEndedEvent) {
//                emitOnPlaybackQueueEnded(event.toBridge())
            }

            override fun onPlaybackRepeatModeChanged(event: PlaybackRepeatModeChangedEvent) {
//                emitOnPlaybackRepeatModeChanged(event.toBridge())
            }

            override fun onPlaybackError(event: PlaybackErrorEvent) {
//                emitOnPlaybackError(event.toBridge())
            }

            override fun onMetadataCommonReceived(metadata: WritableMap) {
//                emitOnMetadataCommonReceived(Arguments.createMap().apply { putMap("metadata", metadata) })
            }

            override fun onMetadataTimedReceived(metadata: Metadata) {
//                emitOnMetadataTimedReceived(
//                    Arguments.createMap().let {
//                        it.putArray(
//                            "metadata",
//                            Arguments.createArray().apply {
//                                MetadataAdapter.Companion.fromMetadata(metadata).forEach { item -> pushMap(item) }
//                            },
//                        )
//                        it
//                    }
//                )
            }

            override fun onPlaybackMetadata(metadata: PlaybackMetadata?) {
//                metadata?.let {
//                    emitOnPlaybackMetadata(
//                        Arguments.createMap().apply {
//                            putString("source", it.source)
//                            putString("title", it.title)
//                            putString("url", it.url)
//                            putString("artist", it.artist)
//                            putString("album", it.album)
//                            putString("date", it.date)
//                            putString("genre", it.genre)
//                        }
//                    )
//                }
            }

            override fun onRemotePlay() {
//                emitOnRemotePlay(Arguments.createMap())
            }

            override fun onRemotePause() {
//                emitOnRemotePause(Arguments.createMap())
            }

            override fun onRemoteStop() {
//                emitOnRemoteStop(Arguments.createMap())
            }

            override fun onRemoteNext() {
                Timber.d("onRemoteNext called - emitting event to JavaScript")
//                emitOnRemoteNext(Arguments.createMap())
            }

            override fun onRemotePrevious() {
                Timber.d("onRemotePrevious called - emitting event to JavaScript")
//                emitOnRemotePrevious(Arguments.createMap())
            }

            override fun onRemoteJumpForward(event: RemoteJumpForwardEvent) {
                Timber.d("onRemoteJumpForward called - emitting event to JavaScript")
//                emitOnRemoteJumpForward(event.toBridge())
            }

            override fun onRemoteJumpBackward(event: RemoteJumpBackwardEvent) {
                Timber.d("onRemoteJumpBackward called - emitting event to JavaScript")
//                emitOnRemoteJumpBackward(event.toBridge())
            }

            override fun onRemoteSeek(event: RemoteSeekEvent) {
//                emitOnRemoteSeek(event.toBridge())
            }

            override fun onRemoteSetRating(event: RemoteSetRatingEvent) {
//                emitOnRemoteSetRating(event.toBridge())
            }


            override fun onOptionsChanged(options: PlayerUpdateOptions) {
//                emitOnOptionsChanged(options.toBridge())
            }

            // Media browser callbacks
            override fun onGetChildrenRequest(requestId: String, parentId: String, page: Int, pageSize: Int) {
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
