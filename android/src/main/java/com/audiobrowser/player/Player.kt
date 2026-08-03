package com.audiobrowser.player

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaMetadata
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.audiobrowser.AudioBrowser
import com.audiobrowser.Callbacks
import com.audiobrowser.browser.displayArtworkSource
import com.audiobrowser.browser.resolveArtworkUrl
import com.audiobrowser.browser.unattributedArtworkSource
import com.audiobrowser.extension.NumberExt.Companion.toSeconds
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.util.CoilBitmapLoader
import com.audiobrowser.util.NetworkConnectivityMonitor
import com.audiobrowser.util.PlayingStateFactory
import com.audiobrowser.util.RepeatModeFactory
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.AppKilledPlaybackBehavior
import com.margelo.nitro.audiobrowser.FavoriteChangedEvent
import com.margelo.nitro.audiobrowser.GateEvent
import com.margelo.nitro.audiobrowser.GateReason
import com.margelo.nitro.audiobrowser.ImageContext
import com.margelo.nitro.audiobrowser.ImageSource
import com.margelo.nitro.audiobrowser.NativeGateRequest
import com.margelo.nitro.audiobrowser.NowPlayingMetadata
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.PlaybackActiveTrackChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackPlayWhenReadyChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackProgressUpdatedEvent
import com.margelo.nitro.audiobrowser.PlaybackQueueEndedEvent
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.PlayingState
import com.margelo.nitro.audiobrowser.RepeatMode
import com.margelo.nitro.audiobrowser.SearchParams
import com.margelo.nitro.audiobrowser.Track
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

@SuppressLint("RestrictedApi")
class Player(internal val context: Context) {
  val appKilledPlaybackBehavior: AppKilledPlaybackBehavior
    get() = options.appKilledPlaybackBehavior

  private var options = PlayerUpdateOptions()
  internal var callbacks: Callbacks? = null
  private lateinit var mediaSession: MediaSession
  val networkMonitor: NetworkConnectivityMonitor = NetworkConnectivityMonitor(context)
  internal val equalizer = EqualizerController { settings ->
    callbacks?.onEqualizerChanged(settings)
  }
  private val mediaSessionCallback = MediaSessionCallback(this)
  internal val playbackStateStore = PlaybackStateStore(this)
  internal val volumeFader = VolumeFader(getVolume = { volume }, setVolume = { volume = it })
  internal val sleepTimer =
    SleepTimerManager(
      volumeFader,
      pause = { pause() },
      onChanged = { callbacks?.onSleepTimerChanged(it) },
    )

  lateinit var exoPlayer: ExoPlayer
  lateinit var forwardingPlayer: androidx.media3.common.Player
  /** Thread-safe cache of playWhenReady for access from non-main threads (e.g., retry policy) */
  @Volatile internal var playWhenReadyCache = false

  /** Last playWhenReady value emitted to JS — see [emitPlayWhenReadyChanged]. */
  private var lastEmittedPlayWhenReady: Boolean? = null

  /**
   * Emits the playWhenReady change to JS exactly once per value. Two producers exist: the
   * synchronous clear at ENDED in [setPlaybackState] (ordered before the state / queue-ended
   * callbacks, matching iOS) and the async media3 listener (authoritative for changes ExoPlayer
   * makes on its own, e.g. audio focus); the guard deduplicates their overlap.
   */
  internal fun emitPlayWhenReadyChanged(value: Boolean) {
    if (lastEmittedPlayWhenReady == value) return
    lastEmittedPlayWhenReady = value
    callbacks?.onPlaybackPlayWhenReadyChanged(PlaybackPlayWhenReadyChangedEvent(value))
  }
  private lateinit var mediaFactory: MediaFactory
  private lateinit var loadControl: DynamicLoadControl
  private var automaticBufferManager: AutomaticBufferManager? = null

  /**
   * Tracks whether a network-related retry is pending. When true, network restoration will trigger
   * an immediate retry via exoPlayer.prepare().
   */
  @Volatile private var pendingNetworkRetry = false

  private var browserRegistered = CompletableDeferred<AudioBrowser>()
  private var _coilBitmapLoader: CoilBitmapLoader? = null

  /**
   * Coil ImageLoader for SVG pre-rendering in Android Auto browse items. Set by Service after
   * creation.
   */
  var imageLoader: coil3.ImageLoader? = null

  /** Registry that maps content:// tokens to artwork URLs for the browse content provider. */
  val browseArtworkRegistry = com.audiobrowser.browser.BrowseArtworkRegistry()

  /**
   * Set the CoilBitmapLoader for display-time bitmap loading. Called from Service after creation.
   */
  var coilBitmapLoader: CoilBitmapLoader?
    get() = _coilBitmapLoader
    set(value) {
      _coilBitmapLoader = value
    }

  // NOTE: setting the browser does NOT complete browserRegistered.
  // Configuration (routes/tabs) must be set before Android Auto can browse.
  // Call notifyBrowserConfigurationReady() after configuration is set.
  var browser: AudioBrowser? = null

  /**
   * Notifies that the browser configuration is ready (routes/tabs are configured). This should be
   * called after setting the browser AND its configuration. Only then will Android Auto be able to
   * browse content.
   */
  fun notifyBrowserConfigurationReady() {
    val audioBrowser = browser ?: return

    if (!browserRegistered.isCompleted) {
      Timber.d("Browser configuration ready - completing deferred")
      browserRegistered.complete(audioBrowser)
      // Notify any subscribed controllers that content is now available
      // This handles the cold-start case where AA subscribed before browser was ready
      mediaSessionCallback.notifyBrowserReady()
    }

    // Update MediaSession commands when browser becomes available with search configured
    // Only update if search is available, since default state is "no search"
    if (::mediaSession.isInitialized) {
      val searchAvailable = audioBrowser.browserManager.config.hasSearch
      if (searchAvailable) {
        mediaSessionCallback.updateMediaSession(
          mediaSession,
          options.capabilities,
          options.notificationButtons,
          searchAvailable,
        )
      }
    }
  }

  /**
   * Suspends until the browser is registered, with a timeout.
   *
   * @throws TimeoutCancellationException if browser is not registered within timeout
   */
  suspend fun awaitBrowser(): AudioBrowser =
    browser
      ?: try {
        withTimeout(10.seconds) {
          Timber.d("Waiting for browser registration...")
          browserRegistered.await()
        }
      } catch (e: TimeoutCancellationException) {
        Timber.e("Timed out waiting for browser registration (10s)")
        throw e
      }

  private lateinit var playerListener: PlayerListener
  private var cache: SimpleCache? = null

  private val progressTimer: PlaybackTimer by lazy {
    PlaybackTimer(
      isActive = {
        it == PlaybackState.LOADING || it == PlaybackState.BUFFERING || it == PlaybackState.PLAYING
      }
    ) {
      val index = currentIndex ?: return@PlaybackTimer
      val event =
        PlaybackProgressUpdatedEvent(
          position = position.toSeconds(),
          duration = duration.toSeconds(),
          buffered = bufferedPosition.toSeconds(),
          track = index.toDouble(),
        )
      callbacks?.onPlaybackProgressUpdated(event)
    }
  }

  private val intervalTimer: PlaybackTimer by lazy {
    PlaybackTimer(isActive = { it == PlaybackState.PLAYING }) { callbacks?.onPlaybackInterval() }
  }

  internal var playingState: PlayingState = PlayingState(false, false)
    private set

  val currentTrack: Track?
    get() = exoPlayer.currentMediaItem?.let { TrackFactory.fromMedia3(it) }

  internal var lastTrack: Track? = null
  internal var lastIndex: Int? = null

  var playbackError: PlaybackError? = null
    internal set

  internal var playbackState: PlaybackState = PlaybackState.NONE
    private set

  fun getPlayback(): Playback {
    return Playback(playbackState, playbackError)
  }

  fun getPlayingState(): PlayingState {
    return playingState
  }

  /**
   * Re-derives [playingState] from the current `playWhenReady` + [playbackState] and emits
   * `onPlaybackPlayingState` when it changed. The only writer of [playingState] — called from the
   * state machine ([setPlaybackState]) and from [PlayerListener.onPlayWhenReadyChanged], the change
   * points of its two inputs.
   */
  internal fun refreshPlayingState() {
    val newPlayingState = PlayingStateFactory.derive(playWhenReady, playbackState)
    if (newPlayingState == playingState) return
    Timber.d(
      "PlayingState changed: playing=${newPlayingState.playing}, buffering=${newPlayingState.buffering}"
    )
    playingState = newPlayingState
    callbacks?.onPlaybackPlayingState(playingState)
  }

  var playWhenReady: Boolean
    get() = exoPlayer.playWhenReady
    set(value) {
      // Raising the intent at STATE_ENDED must replay, not silently no-op:
      // ExoPlayer's setPlayWhenReady(true) never restarts an ended player (the
      // ENDED→seekToDefaultPosition replay lives in media3's media-button
      // path, not the Player API). In the setter so every writer gets it —
      // play(), togglePlayback() and JS setPlayWhenReady alike.
      if (value && exoPlayer.playbackState == ExoPlayer.STATE_ENDED) {
        exoPlayer.seekToDefaultPosition()
      }
      playWhenReadyCache = value
      exoPlayer.playWhenReady = value
    }

  val duration: Long
    get() = if (exoPlayer.duration == C.TIME_UNSET) 0 else exoPlayer.duration

  internal var oldPosition = 0L

  val position: Long
    get() =
      if (exoPlayer.currentPosition == C.INDEX_UNSET.toLong()) 0 else exoPlayer.currentPosition

  val bufferedPosition: Long
    get() =
      if (exoPlayer.bufferedPosition == C.INDEX_UNSET.toLong()) 0 else exoPlayer.bufferedPosition

  var volume: Float
    get() = exoPlayer.volume
    set(value) {
      exoPlayer.volume = value
    }

  var playbackSpeed: Float
    get() = exoPlayer.playbackParameters.speed
    set(value) {
      exoPlayer.setPlaybackSpeed(value)
    }

  val isPlaying
    get() = exoPlayer.isPlaying

  var repeatMode: RepeatMode
    get() = RepeatModeFactory.fromMedia3(exoPlayer.repeatMode)
    internal set(value) {
      exoPlayer.repeatMode = RepeatModeFactory.toMedia3(value)
    }

  val currentIndex: Int?
    get() =
      if (exoPlayer.currentMediaItemIndex == C.INDEX_UNSET) null
      else exoPlayer.currentMediaItemIndex

  var shuffleMode: Boolean
    get() = exoPlayer.shuffleModeEnabled
    set(value) {
      exoPlayer.shuffleModeEnabled = value
    }

  val trackCount: Int
    get() = exoPlayer.mediaItemCount

  val isEmpty: Boolean
    get() = exoPlayer.mediaItemCount == 0

  val tracks: Array<Track>
    get() =
      (0 until exoPlayer.mediaItemCount)
        .map { index -> TrackFactory.fromMedia3(exoPlayer.getMediaItemAt(index)) }
        .toTypedArray()

  /**
   * Whether nothing follows the current item in *playback order* — shuffle- and
   * repeat-aware via media3's own order computation (the linear
   * `currentMediaItemIndex == count - 1` check missed a shuffled queue whose
   * playback order ends on a different linear index). Matches iOS's
   * `isLastInPlaybackOrder`.
   */
  val isLastInPlaybackOrder: Boolean
    get() = exoPlayer.nextMediaItemIndex == C.INDEX_UNSET

  /**
   * The source path from which the current queue was expanded (e.g., from a contextual URL). Used
   * to avoid re-expanding the queue when selecting tracks from the same source.
   */
  var queueSourcePath: String? = null
    internal set

  /**
   * Get track at index with bounds checking.
   *
   * @param index The index of the track to retrieve.
   * @throws IllegalArgumentException if index is out of bounds.
   */
  fun getTrack(index: Int): Track {
    if (index < 0 || index >= exoPlayer.mediaItemCount) {
      throw IllegalArgumentException(
        "Track index $index is out of bounds (size: ${exoPlayer.mediaItemCount})"
      )
    }
    return TrackFactory.fromMedia3(exoPlayer.getMediaItemAt(index))
  }

  var skipSilence: Boolean
    get() = exoPlayer.skipSilenceEnabled
    internal set(value) {
      exoPlayer.skipSilenceEnabled = value
    }

  /**
   * Sets up or recreates the ExoPlayer with the provided setup options. This method can be called
   * multiple times to change setup options.
   */
  fun setup(setupOptions: PlayerSetupOptions) {
    Timber.Forest.d("Setting up player with new options")

    nowPlaying.enabled = setupOptions.autoUpdateNowPlayingMetadata
    // Wrap the Nitro Func into a plain suspend call so the updater (and its tests) never touch
    // bridge types; the double-await depth is unchanged (invoke -> bridge Promise -> JS promise).
    nowPlaying.formatter =
      setupOptions.nowPlayingMetadataFormatter?.let { f -> { params -> f.invoke(params).await() } }

    val isInitialSetup = !::exoPlayer.isInitialized

    if (!isInitialSetup) {
      forwardingPlayer.removeListener(playerListener)
      exoPlayer.release()
      Timber.Forest.d("Player cleanup completed")
    }

    if (setupOptions.maxCacheSize > 0) {
      // SimpleCache locks its folder — re-setup must release the previous
      // instance first or the constructor throws.
      cache?.release()
      cache =
        SimpleCache(
          File(context.cacheDir, "RNAB"),
          LeastRecentlyUsedCacheEvictor(
            setupOptions.maxCacheSize.toLong() * 1_000_000
          ), // MB to bytes
          StandaloneDatabaseProvider(context),
        )
    } else {
      cache?.release()
      cache = null
    }

    // One engine generation: load control, media factory, ExoPlayer (pure construction —
    // see buildPlayerEngine; lifecycle and wiring stay here).
    val engine =
      buildPlayerEngine(
        context,
        setupOptions,
        cache,
        shouldRetry = { playWhenReadyCache },
        isOnline = { networkMonitor.getOnline() },
        onRetryPending = { isNetworkError ->
          // Track pending network retries for acceleration when connectivity returns
          pendingNetworkRetry = isNetworkError
          if (isNetworkError) {
            Timber.d("Network retry pending, will accelerate on connectivity restoration")
          }
        },
        resolveMediaConfig = { url -> browser?.getMediaRequestConfig(url) },
      )
    loadControl = engine.loadControl
    mediaFactory = engine.mediaFactory
    exoPlayer = engine.exoPlayer

    // A rebuilt engine starts paused and fires no change event — sync the
    // eager cache and tell JS, or the stale true sticks (and the emit dedupe
    // would swallow the next real rise).
    if (!isInitialSetup) {
      playWhenReadyCache = false
      emitPlayWhenReadyChanged(false)
    }

    // Recreate forwarding player with new ExoPlayer
    forwardingPlayer =
      InterceptingPlayer(
        exoPlayer,
        { callbacks },
        { options },
        keepSessionAliveOnError = setupOptions.keepSessionAliveOnError,
      )

    if (isInitialSetup) {
      // Initial setup - create player listener and emit initial state
      playerListener = PlayerListener(this)
      forwardingPlayer.addListener(playerListener)
      callbacks?.onPlaybackChanged(Playback(PlaybackState.NONE, null))

      // Initialize equalizer with audio session ID
      equalizer.initialize(exoPlayer.audioSessionId)
    } else {
      // Re-setup - re-add listener and update MediaSession
      forwardingPlayer.addListener(playerListener)

      // Update MediaSession with new forwardingPlayer reference if MediaSession exists
      if (::mediaSession.isInitialized) {
        Timber.Forest.d("Updating MediaSession with new forwardingPlayer reference")
        mediaSession.player = forwardingPlayer
      }

      setPlaybackState(PlaybackState.NONE)
    }

    // Set up automatic buffer management if enabled
    setupAutomaticBufferManager(setupOptions.automaticBuffer)
  }

  /** Sets up or tears down the automatic buffer manager based on the enabled flag. */
  private fun setupAutomaticBufferManager(enabled: Boolean) {
    // Detach existing manager if any
    automaticBufferManager?.detach()

    if (enabled) {
      val defaultConfig = loadControl.getBufferConfig()
      automaticBufferManager =
        AutomaticBufferManager(loadControl, defaultConfig).also { it.attach(exoPlayer) }
      Timber.d("Automatic buffer management enabled")
    } else {
      automaticBufferManager = null
      Timber.d("Automatic buffer management disabled")
    }
  }

  /**
   * Starts observing network connectivity changes and invokes the callback when state changes. Also
   * handles accelerating pending network retries when connectivity is restored.
   *
   * @param scope The coroutine scope to use for observation
   */
  fun observeNetworkConnectivity(scope: kotlinx.coroutines.CoroutineScope) {
    networkMonitor.observeOnline(scope) { isOnline ->
      callbacks?.onOnlineChanged(isOnline)

      // Re-render now-playing so the formatter's stall classification (buffering vs offline) tracks
      // connectivity immediately, not only on the next playback transition.
      nowPlaying.render()

      // Accelerate pending network retry when connectivity is restored
      if (isOnline && pendingNetworkRetry) {
        Timber.d("Network restored with pending retry, triggering immediate retry")
        pendingNetworkRetry = false
        // Only retry if player is still expecting to play
        if (playWhenReadyCache) {
          exoPlayer.prepare()
        }
      }
    }
  }

  /**
   * Loads a track into the player. If there is a current track, it will be replaced. If the queue
   * is empty, the track will be added.
   *
   * @param track The [Track] to load.
   */
  fun load(track: Track) {
    if (exoPlayer.mediaItemCount == 0) {
      add(track)
    } else {
      val index = exoPlayer.currentMediaItemIndex
      replaceTrack(index, track)
      exoPlayer.seekTo(index, C.TIME_UNSET)
      exoPlayer.prepare()
    }
  }

  /**
   * Add a single track to the queue. If the AudioPlayer has no track loaded, it will load the
   * `track`.
   *
   * @param track The [Track] to add.
   */
  fun add(track: Track) {
    val mediaItem = TrackFactory.toMedia3(track)
    exoPlayer.addMediaItem(mediaItem)
    exoPlayer.prepare()
  }

  /**
   * Add multiple tracks to the queue. If the AudioPlayer has no track loaded, it will load the
   * first track in the list.
   *
   * @param tracks The [Track]s to add.
   */
  fun add(tracks: Array<Track>) {
    val mediaItems = TrackFactory.toMedia3(tracks)
    exoPlayer.addMediaItems(mediaItems.toList())
    exoPlayer.prepare()
  }

  /**
   * Add multiple tracks to the queue.
   *
   * @param tracks The [Track]s to add.
   * @param atIndex Index to insert tracks at. Use -1 to append to the end of the queue.
   * @throws IllegalArgumentException if index is out of bounds.
   */
  fun add(tracks: Array<Track>, atIndex: Int) {
    validateInsertIndex(atIndex)
    val index = if (atIndex == -1) exoPlayer.mediaItemCount else atIndex
    val mediaItems = tracks.map { TrackFactory.toMedia3(it) }
    exoPlayer.addMediaItems(index, mediaItems)
    exoPlayer.prepare()
  }

  /**
   * Remove a track from the queue.
   *
   * @param index The index of the track to remove.
   * @throws IllegalArgumentException if index is out of bounds.
   */
  fun remove(index: Int) {
    validateIndex(index)
    exoPlayer.removeMediaItem(index)
  }

  /**
   * Remove tracks from the queue.
   *
   * @param indexes The indexes of the tracks to remove.
   * @throws IllegalArgumentException if any index is out of bounds or if duplicate indexes are
   *   provided.
   */
  fun remove(indexes: List<Int>) {
    if (indexes.toSet().size != indexes.size) {
      throw IllegalArgumentException("Duplicate indexes provided")
    }
    indexes.forEach { validateIndex(it) }
    val sorted = indexes.sortedDescending()
    sorted.forEach { exoPlayer.removeMediaItem(it) }
  }

  /**
   * Skip to the next track in the queue, which may depend on the current repeat mode. Does nothing
   * if there is no next track to skip to.
   */
  fun next() {
    exoPlayer.seekToNextMediaItem()
    exoPlayer.prepare()
  }

  /**
   * Skip to the previous track in the queue, which may depend on the current repeat mode. Does
   * nothing if there is no previous track to skip to.
   */
  fun previous() {
    exoPlayer.seekToPreviousMediaItem()
    exoPlayer.prepare()
  }

  /**
   * Move an track in the queue from one position to another.
   *
   * @param fromIndex The index of the track to move.
   * @param toIndex The index to move the track to. If the index is larger than the size of the
   *   queue, the track is moved to the end of the queue instead.
   * @throws IllegalArgumentException if fromIndex is out of bounds.
   */
  fun move(fromIndex: Int, toIndex: Int) {
    validateIndex(fromIndex)
    exoPlayer.moveMediaItem(fromIndex, toIndex)
  }

  /**
   * Skips to a track in the queue.
   *
   * @param index the index to skip to
   * @throws IllegalArgumentException if index is out of bounds.
   */
  fun skipTo(index: Int) {
    validateIndex(index)
    exoPlayer.seekTo(index, C.TIME_UNSET)
    exoPlayer.prepare()
  }

  /**
   * Sets the queue with new tracks, optionally starting at a specific index and position. This is
   * more efficient than calling clear() + add() + skipTo() separately.
   *
   * @param tracks The tracks to set as the new queue.
   * @param startIndex The index to start playback from (default: 0).
   * @param startPositionMs The position in milliseconds to start from (default: 0).
   * @param sourcePath Optional path from which this queue was expanded (for contextual URL
   *   optimization).
   */
  fun setQueue(
    tracks: Array<Track>,
    startIndex: Int = 0,
    startPositionMs: Long = 0,
    sourcePath: String? = null,
  ) {
    val mediaItems = TrackFactory.toMedia3(tracks).toMutableList()
    exoPlayer.setMediaItems(mediaItems, startIndex, startPositionMs)
    queueSourcePath = sourcePath
    exoPlayer.prepare()
  }

  /**
   * Replaces track at index in queue.
   *
   * @throws IllegalArgumentException if index is out of bounds.
   */
  fun replaceTrack(index: Int, track: Track) {
    validateIndex(index)
    exoPlayer.replaceMediaItem(index, TrackFactory.toMedia3(track))
  }

  /**
   * Sets the favorited state of the currently playing track. Updates the heart icon in media
   * controllers without interrupting playback.
   */
  fun setActiveTrackFavorited(favorited: Boolean) {
    val currentTrack = this.currentTrack ?: return

    // Persist to the native favorites cache regardless of player state — the
    // user's gesture is durable even when there's no active media item to
    // update (queue tear-down, between-track gaps, etc.).
    currentTrack.src?.let { src -> browser?.browserManager?.updateFavorite(src, favorited) }

    val index = exoPlayer.currentMediaItemIndex
    if (index == C.INDEX_UNSET) return

    // Create updated Track with new favorited state
    val updatedTrack = currentTrack.copy(favorited = favorited)

    // Use buildUpon() on the existing MediaItem to update only the metadata
    // This preserves internal references and avoids playback interruption
    // Note: setTag() requires setUri() to be called, so we must re-set the URI
    val currentMediaItem = exoPlayer.getMediaItemAt(index)
    val updatedMetadata =
      currentMediaItem.mediaMetadata.buildUpon().setUserRating(HeartRating(favorited)).build()
    val updatedMediaItem =
      currentMediaItem
        .buildUpon()
        .setUri(currentMediaItem.localConfiguration?.uri)
        .setMediaMetadata(updatedMetadata)
        .setTag(updatedTrack)
        .build()

    exoPlayer.replaceMediaItem(index, updatedMediaItem)

    // Update the heart button icon in notification/Android Auto
    updateFavoriteButtonState(favorited)

    // Notify JS of the favorite state change
    callbacks?.onFavoriteChanged(FavoriteChangedEvent(updatedTrack, favorited))

    // Emit active track changed so useActiveTrack() hook updates
    val activeTrackEvent =
      PlaybackActiveTrackChangedEvent(
        lastIndex = index.toDouble(),
        lastTrack = currentTrack,
        lastPosition = exoPlayer.currentPosition.toSeconds(),
        index = index.toDouble(),
        track = updatedTrack,
      )
    callbacks?.onPlaybackActiveTrackChanged(activeTrackEvent)

    // Emit queue changed so useQueue() hook updates
    callbacks?.onPlaybackQueueChanged(tracks)
  }

  /** Toggles the favorited state of the currently playing track. */
  fun toggleActiveTrackFavorited() {
    val currentTrack = this.currentTrack ?: return
    setActiveTrackFavorited(currentTrack.favorited != true)
  }

  // MARK: - Now Playing Metadata

  /**
   * Now Playing rendering (flash/override/formatter precedence, dedupe, stale guards, artwork
   * keying) lives in [NowPlayingUpdater]; this object is its platform surface — current playback
   * reads plus MediaItem stamping via [replaceMediaItem], and artwork resolution via the browser.
   */
  val nowPlaying: NowPlayingUpdater =
    NowPlayingUpdater(
      object : NowPlayingSurface {
        override val currentIndex
          get() = this@Player.currentIndex

        override val currentTrack
          get() = this@Player.currentTrack

        override val playbackState
          get() = this@Player.playbackState

        override val playbackError
          get() = this@Player.playbackError

        override val playWhenReady
          get() = exoPlayer.playWhenReady

        override val isRebuffering
          get() = loadControl.isRebuffering

        override val isOnline
          get() = networkMonitor.getOnline()

        override val hasNowPlayingArtworkConfig
          get() = browser?.browserManager?.config?.nowPlayingArtwork != null

        override fun stampFields(
          index: Int,
          track: Track,
          title: String?,
          secondaryLine: String?,
          album: String?,
        ) =
          restampMediaItem(index, track) {
            setTitle(title)
              .setDisplayTitle(title)
              .setArtist(secondaryLine)
              // Android Auto reads DISPLAY_SUBTITLE (not ARTIST) once DISPLAY_TITLE is set, so
              // mirror the line into `subtitle`; `artist` still drives the lock screen /
              // notification.
              .setSubtitle(secondaryLine)
              .setAlbumTitle(album)
          }

        override fun stampArtwork(index: Int, track: Track, uri: String) =
          restampMediaItem(index, track) { setArtworkUri(uri.toUri()) }

        override suspend fun resolveNowPlayingArtwork(track: Track, sizePx: Double): String? {
          val browserManager = browser?.browserManager ?: return null
          val config = browserManager.config.nowPlayingArtwork ?: return null
          val resolved =
            try {
              browserManager.resolveArtworkUrl(track, config, ImageContext(sizePx, sizePx))
            } catch (e: Exception) {
              Timber.e(e, "Failed to resolve now-playing artwork for track id=${track.id}")
              null
            }
          val uri = resolved?.uri?.takeIf { it.isNotEmpty() } ?: return null
          // Remember how this URI was produced: Media3 hands it back to the bitmap loader, which
          // re-resolves Track-first (with the nowPlayingArtwork kind, not the global artwork
          // config).
          browserManager.artworkResolutions.register(uri, track, config)
          return uri
        }

        override fun emitNowPlayingChanged(metadata: NowPlayingMetadata) {
          callbacks?.onNowPlayingChanged(metadata)
        }
      },
      MainScope(),
    )

  /** Republishes the item at [index] with mutated metadata, preserving uri and Track tag. */
  private fun restampMediaItem(
    index: Int,
    track: Track,
    mutate: MediaMetadata.Builder.() -> MediaMetadata.Builder,
  ) {
    val currentMediaItem = exoPlayer.getMediaItemAt(index)
    val updatedMediaItem =
      currentMediaItem
        .buildUpon()
        .setUri(currentMediaItem.localConfiguration?.uri)
        .setMediaMetadata(currentMediaItem.mediaMetadata.buildUpon().mutate().build())
        .setTag(track)
        .build()
    exoPlayer.replaceMediaItem(index, updatedMediaItem)
  }

  /**
   * Resets the retry timer when track changes. Called from PlayerListener.onMediaItemTransition.
   */
  internal fun resetRetryTimer() {
    mediaFactory.resetRetryTimer()
  }

  /**
   * Finds the queue Track whose published artwork URI matches [uri], for display-time artwork
   * resolution of app-supplied queue tracks that never went through browse (so they are not in the
   * resolution registry). Must run on the main thread (ExoPlayer access).
   */
  private fun findQueueTrackByArtworkUri(uri: String): Track? {
    for (i in 0 until exoPlayer.mediaItemCount) {
      val item = exoPlayer.getMediaItemAt(i)
      if (item.mediaMetadata.artworkUri?.toString() == uri) {
        return item.localConfiguration?.tag as? Track
      }
    }
    return null
  }

  /**
   * Track-first display-time artwork resolution for [CoilBitmapLoader]: registry hit
   * (browse/now-playing-resolved URIs) → queue-tag lookup (app-supplied tracks with raw artwork) →
   * header-only fallback for unattributable URIs (registry eviction / process-death restore). Null
   * means "fetch the URI as-is".
   */
  suspend fun resolveDisplayArtwork(uri: String, sizeHintPixels: Int?): ImageSource? {
    val browserManager = browser?.browserManager ?: return null
    val imageContext =
      sizeHintPixels?.takeIf { it > 0 }?.let { ImageContext(it.toDouble(), it.toDouble()) }
    browserManager.displayArtworkSource(uri, imageContext)?.let {
      return it
    }
    withContext(Dispatchers.Main) { findQueueTrackByArtworkUri(uri) }
      ?.let { track ->
        return browserManager.resolveArtworkUrl(track, null, imageContext)
      }
    return browserManager.unattributedArtworkSource(uri)
  }

  /**
   * Updates the favorite button icon in the notification/Android Auto. Call this when track changes
   * or favorite state changes.
   */
  internal fun updateFavoriteButtonState(favorited: Boolean?) {
    if (!::mediaSession.isInitialized) return
    mediaSessionCallback.commandManager.updateFavoriteState(mediaSession, favorited)
  }

  /** Removes all the upcoming tracks, if any (the ones returned by [next]). */
  fun removeUpcomingTracks() {
    val index = exoPlayer.currentMediaItemIndex
    if (index == C.INDEX_UNSET) return
    val lastIndex = exoPlayer.mediaItemCount
    val fromIndex = index + 1

    exoPlayer.removeMediaItems(fromIndex, lastIndex)
  }

  fun play() {
    // Through the setter so the STATE_ENDED replay handling applies
    // (ExoPlayer.play() is only setPlayWhenReady(true)).
    playWhenReady = true
    if (currentTrack != null) {
      // No-op unless the player is STATE_IDLE (ExoPlayer.prepare early-returns
      // otherwise), so this only reconnects after a stop() or error and never
      // re-buffers a healthy stream. Reconnecting is also how live streams
      // rejoin the live edge on resume.
      exoPlayer.prepare()
    }
  }

  /** Jump to the live edge (default position) of a live item; no-op otherwise. */
  fun seekToLiveEdge() {
    if (exoPlayer.isCurrentMediaItemLive) {
      exoPlayer.seekToDefaultPosition()
    }
  }

  /**
   * Executes a search and plays the results. Used for voice commands with structured search
   * parameters.
   *
   * @param params The structured search parameters (mode, query, artist, album, etc.)
   * @return true if search succeeded and playback started, false otherwise
   */
  suspend fun playFromSearch(params: SearchParams): Boolean {
    return try {
      val audioBrowser = awaitBrowser()
      // A gate blocks external-surface "play" search too — otherwise voice search is a way around
      // the gate (mirrors the iOS play-media intent guard). Resolved per request; a gated decision
      // fires onGate(search) and refuses.
      val gateOutcome =
        audioBrowser.gateDecision(
          NativeGateRequest(reason = GateReason.SEARCH, path = null, search = params)
        )
      if (gateOutcome.gated) {
        Timber.i("playFromSearch refused — request is gated")
        audioBrowser.onGate(GateEvent(GateReason.SEARCH))
        return false
      }
      val browserManager = audioBrowser.browserManager

      Timber.d(
        "Executing voice search: mode=${params.mode}, query='${params.query}', artist='${params.artist}', album='${params.album}'"
      )
      val tracks = browserManager.searchPlayable(params)

      if (tracks != null && tracks.isNotEmpty()) {
        Timber.d("Found ${tracks.size} track(s), playing first: ${tracks[0].title}")
        setQueue(tracks)
        play()
        true
      } else {
        Timber.w("No tracks found for search: ${params}")
        false
      }
    } catch (e: Exception) {
      Timber.e(e, "Error handling voice search: ${params}")
      false
    }
  }

  fun prepare() {
    if (currentTrack != null) {
      exoPlayer.prepare()
    }
  }

  fun pause() {
    // Through the setter so the eager playWhenReadyCache write holds for the
    // drop direction too — the retry policy reads it off-main before
    // ExoPlayer's listener round-trip syncs it.
    playWhenReady = false
  }

  fun togglePlayback() {
    if (exoPlayer.playWhenReady) {
      pause()
    } else {
      play()
    }
  }

  /**
   * Stops playback, without clearing the active track. Calling this method will cause the playback
   * state to transition to State.NONE and the player will release the loaded media and resources
   * required for playback.
   */
  fun stop() {
    // State first so the machine's STOPPED guard suppresses the PAUSED transition
    // from the playWhenReady drop below; through setPlaybackState so the change
    // is emitted (direct field assignment bypasses events — see its docstring).
    setPlaybackState(PlaybackState.STOPPED)
    playWhenReady = false
    exoPlayer.stop()
  }

  fun clear() {
    exoPlayer.clearMediaItems()
    queueSourcePath = null
  }

  /**
   * Stops and destroys the player. Only call this when you are finished using the player, otherwise
   * use [pause].
   */
  fun destroy() {
    // Cancel without a final save: the loop would tick against the released
    // player and persist position 0 over the real resumption position.
    playbackStateStore.cancelPeriodicSave()
    // An armed timer's Runnable would outlive the release and fire pause() into the
    // released player. Cleared here, while a mid-ramp fade can still restore volume.
    sleepTimer.clear()
    stop()
    nowPlaying.destroy()
    mediaSessionCallback.destroy()
    forwardingPlayer.removeListener(playerListener)
    automaticBufferManager?.detach()
    automaticBufferManager = null
    exoPlayer.release()
    cache?.release()
    cache = null
    networkMonitor.destroy()
    equalizer.release()
  }

  fun seekTo(duration: Long, unit: TimeUnit) {
    val positionMs = TimeUnit.MILLISECONDS.convert(duration, unit)
    exoPlayer.seekTo(positionMs)
  }

  fun seekBy(offset: Long, unit: TimeUnit) {
    val positionMs = exoPlayer.currentPosition + TimeUnit.MILLISECONDS.convert(offset, unit)
    exoPlayer.seekTo(positionMs)
  }

  /**
   * Updates the player state and emits a state change event if the state has changed. Only emits an
   * event if the new state differs from the current state.
   *
   * IMPORTANT: This method also triggers the queue ended event when the player reaches State.ENDED
   * and is on the last track. All state transitions should go through this method to ensure proper
   * event dispatching. Direct assignments to playerState will bypass event emission.
   *
   * @param state The new player state to set
   */
  internal fun setPlaybackState(state: PlaybackState) {
    if (state != playbackState) {
      val oldState = playbackState
      playbackState = state

      // A natural end exhausts the play intent — nothing is left to play. Keeping
      // playWhenReady true inverted the play/pause toggle, held audio focus forever
      // (ExoPlayer abandons it only at IDLE or on the intent dropping), and kept the
      // periodic position save running. Cleared and emitted before the state /
      // queue-ended callbacks so JS observes the native order (intent → state →
      // queueEnded); the media3 listener's later echo is deduplicated by
      // emitPlayWhenReadyChanged. The state machine's ENDED guard keeps the drop
      // from re-reporting the state as PAUSED.
      if (state == PlaybackState.ENDED && playWhenReady) {
        playWhenReady = false
        emitPlayWhenReadyChanged(false)
      }

      // The last track fires no AUTO media-item transition, so an armed
      // end-of-track sleep timer would stay set forever and pause the next
      // track played instead.
      if (state == PlaybackState.ENDED) {
        sleepTimer.onTrackEnd()
      }

      // Clear error when transitioning away from error state
      if (oldState == PlaybackState.ERROR) {
        playbackError = null
        callbacks?.onPlaybackError(null)
      }

      val playback = Playback(state, playbackError)
      callbacks?.onPlaybackChanged(playback)

      // Re-render the now-playing on every state change: the metadata formatter receives
      // `playbackState` (plus isRebuffering / isOnline / error), so any transition can change its
      // output — the live song while paused, a "Reconnecting…" line on a rebuffer, an offline/error
      // line. The publish-dedupe in applyNowPlayingFields drops redundant updates, so re-running
      // through the rapid startup sequence (none→loading→buffering→ready→playing) is cheap.
      nowPlaying.render()

      // Emit queue ended event when playback ends on the last track. Repeat modes never
      // conceptually end the queue (matches web's endsQueue()): an ENDED that surfaces while
      // repeating (e.g. a seek to the end) must not read as "playlist over".
      // This coupling ensures queue ended events are always triggered consistently with state
      // changes
      if (state == PlaybackState.ENDED && isLastInPlaybackOrder && repeatMode == RepeatMode.OFF) {
        currentIndex?.let { index ->
          val event =
            PlaybackQueueEndedEvent(track = index.toDouble(), position = position.toSeconds())
          callbacks?.onPlaybackQueueEnded(event)
        }
        // Reset saved position to 0 so resumption starts from beginning
        playbackStateStore.savePositionZero()
      }

      progressTimer.onPlaybackStateChanged(state)
      intervalTimer.onPlaybackStateChanged(state)
      refreshPlayingState()
    }
  }

  /**
   * Sets the progress update interval.
   *
   * @param interval The interval in seconds, or null to disable progress updates
   */
  fun setProgressUpdateInterval(interval: Double?) {
    progressTimer.setInterval(interval)
  }

  fun setPlaybackIntervalEnabled(enabled: Boolean) {
    intervalTimer.setInterval(if (enabled) 1.0 else null)
  }

  /**
   * Applies update options with change detection. Only updates properties that have actually
   * changed and emits events accordingly.
   *
   * @param options The new options to apply
   * @param mediaSession The MediaSession to update when capabilities change
   */
  fun applyOptions(options: PlayerUpdateOptions) {
    // Store previous values for change detection
    val previousOptions = this.options

    // Update current options
    this.options = options.copy()

    // Check what changed
    val skipSilenceChanged = previousOptions.skipSilence != options.skipSilence
    val progressUpdateEventIntervalChanged =
      previousOptions.progressUpdateEventInterval != options.progressUpdateEventInterval
    val forwardJumpIntervalChanged =
      previousOptions.forwardJumpInterval != options.forwardJumpInterval
    val backwardJumpIntervalChanged =
      previousOptions.backwardJumpInterval != options.backwardJumpInterval
    val capabilitiesChanged = previousOptions.capabilities != options.capabilities
    val notificationButtonsChanged =
      previousOptions.notificationButtons != options.notificationButtons
    val appKilledPlaybackBehaviorChanged =
      previousOptions.appKilledPlaybackBehavior != options.appKilledPlaybackBehavior

    val hasChanged =
      skipSilenceChanged ||
        progressUpdateEventIntervalChanged ||
        forwardJumpIntervalChanged ||
        backwardJumpIntervalChanged ||
        capabilitiesChanged ||
        notificationButtonsChanged ||
        appKilledPlaybackBehaviorChanged

    // Apply only changed properties
    if (skipSilenceChanged) {
      skipSilence = options.skipSilence
    }

    if (capabilitiesChanged) {
      // The `favorite` capability is the single favoriting switch — propagate
      // its match mode to the browser so it can hydrate row hearts.
      browser?.browserManager?.setFavoriteMatch(options.capabilities.favoriteMatch)
    }

    if (progressUpdateEventIntervalChanged) {
      setProgressUpdateInterval(options.progressUpdateEventInterval)
    }

    if (capabilitiesChanged || notificationButtonsChanged) {
      val searchAvailable = browser?.browserManager?.config?.hasSearch ?: false
      mediaSessionCallback.updateMediaSession(
        mediaSession,
        options.capabilities,
        options.notificationButtons,
        searchAvailable,
      )
    }

    if (hasChanged) {
      callbacks?.onOptionsChanged(options)
    }
  }

  /**
   * Sets the callbacks for player events.
   *
   * @param callbacks The callbacks to set, or null to clear callbacks
   */
  fun setCallbacks(callbacks: Callbacks?) {
    this.callbacks = callbacks
  }

  /**
   * Gets the current callbacks instance.
   *
   * @return The current callbacks, or null if none are set
   */
  fun getCallbacks(): Callbacks? {
    return this.callbacks
  }

  fun setMediaSession(mediaSession: MediaSession) {
    this.mediaSession = mediaSession
  }

  fun getOptions(): PlayerUpdateOptions {
    return options
  }

  /**
   * Gets the current network connectivity state.
   *
   * @return true if device is online, false otherwise
   */
  fun getOnline(): Boolean {
    return networkMonitor.getOnline()
  }

  /**
   * Validates that an index is within bounds [0, trackCount).
   *
   * @param index The index to validate.
   * @throws IllegalArgumentException if index is out of bounds.
   */
  private fun validateIndex(index: Int) {
    if (index < 0 || index >= exoPlayer.mediaItemCount) {
      throw IllegalArgumentException(
        "Track index $index is out of bounds (size: ${exoPlayer.mediaItemCount})"
      )
    }
  }

  /**
   * Validates that an insertion index is within bounds [0, trackCount] or -1 (append).
   *
   * @param index The index to validate.
   * @throws IllegalArgumentException if index is out of bounds.
   */
  private fun validateInsertIndex(index: Int) {
    if (index < -1 || index > exoPlayer.mediaItemCount) {
      throw IllegalArgumentException(
        "Insert index $index is out of bounds (size: ${exoPlayer.mediaItemCount}, use -1 to append)"
      )
    }
  }

  /**
   * Gets the MediaSessionCallback for this AudioBrowser.
   *
   * @return MediaLibrarySession.Callback instance
   */
  fun getMediaSessionCallback(): MediaLibraryService.MediaLibrarySession.Callback {
    return mediaSessionCallback
  }

  /**
   * Notifies external controllers (Android Auto, etc.) that content at the given path has changed.
   * Controllers subscribed to this path will refresh their UI.
   *
   * @param path The path where content has changed
   */
  fun notifyContentChanged(path: String) {
    mediaSessionCallback.notifyContentChanged(path)
  }

  fun invalidateAllContent() {
    mediaSessionCallback.invalidateAllContent()
  }

  /**
   * Returns the recommended artwork size in pixels from the connected media browser (e.g., Android
   * Auto), or null if not provided.
   */
  val artworkSizeHintPixels: Int?
    get() = mediaSessionCallback.artworkSizeHintPixels

  /** Returns true if the current media item is a live stream. */
  val isCurrentItemLive: Boolean
    get() = exoPlayer.isCurrentMediaItemLive

  // MARK: - Buffer Configuration

  /**
   * Updates the buffer configuration at runtime.
   *
   * The new configuration takes effect immediately for future buffering decisions. Already-buffered
   * data is not affected.
   *
   * @param config The new buffer configuration to apply.
   */
  fun updateBufferConfig(config: BufferConfig) {
    loadControl.updateBufferConfig(config)
  }

  /**
   * Gets the current buffer configuration.
   *
   * @return The current buffer configuration.
   */
  fun getBufferConfig(): BufferConfig {
    return loadControl.getBufferConfig()
  }

  /** Resets the buffer configuration to defaults. */
  fun resetBufferConfig() {
    loadControl.resetToDefaults()
  }
}
