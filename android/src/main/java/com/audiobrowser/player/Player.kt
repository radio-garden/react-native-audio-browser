package com.audiobrowser.player

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.audiobrowser.AudioBrowser
import com.audiobrowser.Callbacks
import com.audiobrowser.extension.NumberExt.Companion.toSeconds
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.util.AndroidAudioContentTypeFactory
import com.audiobrowser.util.EqualizerManager
import com.audiobrowser.util.NetworkConnectivityMonitor
import com.audiobrowser.util.PlayingStateFactory
import com.audiobrowser.util.RepeatModeFactory
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.AndroidPlayerWakeMode
import com.margelo.nitro.audiobrowser.AppKilledPlaybackBehavior
import com.margelo.nitro.audiobrowser.FavoriteChangedEvent
import com.margelo.nitro.audiobrowser.NowPlayingMetadata
import com.margelo.nitro.audiobrowser.NowPlayingUpdate
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.PlaybackActiveTrackChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackProgressUpdatedEvent
import com.margelo.nitro.audiobrowser.PlaybackQueueEndedEvent
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.PlayingState
import com.margelo.nitro.audiobrowser.RatingType
import com.margelo.nitro.audiobrowser.RemoteJumpBackwardEvent
import com.margelo.nitro.audiobrowser.RemoteJumpForwardEvent
import com.margelo.nitro.audiobrowser.RemoteSeekEvent
import com.margelo.nitro.audiobrowser.RepeatMode
import com.margelo.nitro.audiobrowser.SearchParams
import com.margelo.nitro.audiobrowser.SleepTimer as NitroSleepTimer
import com.margelo.nitro.audiobrowser.SleepTimerEndOfTrack
import com.margelo.nitro.audiobrowser.SleepTimerTime
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.core.NullType
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
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
  private var equalizerManager: EqualizerManager? = null
  private val mediaSessionCallback = MediaSessionCallback(this)
  internal val playbackStateStore = PlaybackStateStore(this)
  private val sleepTimer =
    object : SleepTimer() {
      override fun onComplete() {
        Timber.d("Sleep timer completed, stopping playback")
        stop()
        callbacks?.onSleepTimerChanged(NitroSleepTimer.create(NullType.NULL))
      }
    }

  lateinit var exoPlayer: ExoPlayer
  lateinit var forwardingPlayer: androidx.media3.common.Player
  private lateinit var mediaFactory: MediaFactory
  private lateinit var loadControl: DynamicLoadControl
  private var automaticBufferManager: AutomaticBufferManager? = null

  private var _browser: AudioBrowser? = null
  private var browserRegistered = CompletableDeferred<AudioBrowser>()

  var browser: AudioBrowser?
    get() = _browser
    set(value) {
      _browser = value
      value?.let { audioBrowser ->
        // Complete the deferred when browser is registered
        if (!browserRegistered.isCompleted) {
          Timber.d("Browser registered - completing deferred")
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

  /**
   * ForwardingPlayer that intercepts external player actions and dispatches them to callbacks.
   *
   * This class blocks all external media item modifications to delegate control to RNAB. These
   * overrides prevent media controllers (like Android Auto, notifications) from directly modifying
   * the queue, ensuring all queue changes go through the RNAB API for proper state management and
   * event handling.
   */
  private inner class InterceptingPlayer(player: ExoPlayer) : ForwardingPlayer(player) {

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
      return super.setMediaItems(mediaItems, resetPosition)
    }

    override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
      return super.addMediaItems(mediaItems)
    }

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
      return super.addMediaItems(index, mediaItems)
    }

    override fun setMediaItems(
      mediaItems: MutableList<MediaItem>,
      startIndex: Int,
      startPositionMs: Long,
    ) {
      return super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
      return super.setMediaItems(mediaItems)
    }

    // Intercept playback controls and dispatch to callbacks or fall back to default behavior
    override fun play() {
      if (callbacks?.handleRemotePlay() != true) {
        super.play()
      }
    }

    override fun pause() {
      if (callbacks?.handleRemotePause() != true) {
        super.pause()
      }
    }

    override fun seekToNext() {
      Timber.Forest.d("InterceptingPlayer.seekToNext() called")
      if (callbacks?.handleRemoteNext() != true) {
        super.seekToNext()
      }
    }

    override fun seekToNextMediaItem() {
      Timber.Forest.d("InterceptingPlayer.seekToNextMediaItem() called")
      if (callbacks?.handleRemoteNext() != true) {
        super.seekToNextMediaItem()
      }
    }

    override fun seekToPrevious() {
      Timber.Forest.d("InterceptingPlayer.seekToPrevious() called")
      if (callbacks?.handleRemotePrevious() != true) {
        super.seekToPrevious()
      }
    }

    override fun seekToPreviousMediaItem() {
      Timber.Forest.d("InterceptingPlayer.seekToPreviousMediaItem() called")
      if (callbacks?.handleRemotePrevious() != true) {
        super.seekToPreviousMediaItem()
      }
    }

    override fun seekForward() {
      Timber.Forest.d("InterceptingPlayer.seekForward() called")
      if (
        callbacks?.handleRemoteJumpForward(
          RemoteJumpForwardEvent(interval = options.forwardJumpInterval)
        ) != true
      ) {
        super.seekForward()
      }
    }

    override fun seekBack() {
      if (
        callbacks?.handleRemoteJumpBackward(
          RemoteJumpBackwardEvent(interval = options.backwardJumpInterval)
        ) != true
      ) {
        super.seekBack()
      }
    }

    override fun stop() {
      if (callbacks?.handleRemoteStop() != true) {
        super.stop()
      }
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
      if (
        callbacks?.handleRemoteSeek(RemoteSeekEvent(position = positionMs.toDouble() / 1000.0)) !=
          true
      ) {
        super.seekTo(mediaItemIndex, positionMs)
      }
    }

    override fun seekTo(positionMs: Long) {
      if (
        callbacks?.handleRemoteSeek(RemoteSeekEvent(position = positionMs.toDouble() / 1000.0)) !=
          true
      ) {
        super.seekTo(positionMs)
      }
    }
  }

  private lateinit var playerListener: PlayerListener
  private var cache: SimpleCache? = null

  private val progressUpdateManager: PlaybackProgressUpdateManager by lazy {
    PlaybackProgressUpdateManager {
      val index = currentIndex ?: return@PlaybackProgressUpdateManager
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

  internal var playingState: PlayingState = PlayingState(false, false)

  val currentTrack: Track?
    get() = exoPlayer.currentMediaItem?.let { TrackFactory.fromMedia3(it) }

  internal var lastTrack: Track? = null
  internal var lastIndex: Int? = null

  var playbackError: PlaybackError? = null
    internal set

  internal var playbackState: PlaybackState = PlaybackState.NONE
    private set

  /** Current now playing metadata override (null = use track metadata) */
  private var nowPlayingOverride: NowPlayingUpdate? = null

  fun getPlayback(): Playback {
    return Playback(playbackState, playbackError)
  }

  fun getPlayingState(): PlayingState {
    return playingState
  }

  var playWhenReady: Boolean
    get() = exoPlayer.playWhenReady
    set(value) {
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

  var ratingType: RatingType = RatingType.NONE

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

  val isLastTrack: Boolean
    get() = exoPlayer.currentMediaItemIndex == exoPlayer.mediaItemCount - 1

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

    val isInitialSetup = !::exoPlayer.isInitialized

    if (!isInitialSetup) {
      forwardingPlayer.removeListener(playerListener)
      exoPlayer.release()
      Timber.Forest.d("Player cleanup completed")
    }

    if (setupOptions.maxCacheSize > 0) {
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

    // Recreate ExoPlayer with new setup options
    val renderer = DefaultRenderersFactory(context)
    renderer.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

    // Create bandwidth meter for adaptive bitrate selection in HLS/DASH
    val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()

    loadControl = run {
      val minBuffer = setupOptions.minBuffer.toInt()
      val maxBuffer = setupOptions.maxBuffer.toInt()
      val playBuffer = setupOptions.playBuffer.toInt()
      // When automatic (rebufferBuffer is null), start at playBuffer and let AutomaticBufferManager
      // adjust
      // When fixed (rebufferBuffer is set), use that value
      val playAfterRebuffer = setupOptions.rebufferBuffer?.toInt() ?: playBuffer
      val backBuffer = setupOptions.backBuffer.toInt()
      val config =
        BufferConfig(
          minBufferMs = minBuffer,
          maxBufferMs = maxBuffer,
          bufferForPlaybackMs = playBuffer,
          bufferForPlaybackAfterRebufferMs = playAfterRebuffer,
          backBufferMs = backBuffer,
        )
      DynamicLoadControl(initialConfig = config)
    }
    // Create MediaFactory with reference to browser for media URL transformation
    // shouldRetry checks playWhenReady to avoid retrying when paused (e.g., another app took audio
    // focus)
    mediaFactory =
      MediaFactory(
        context,
        cache,
        setupOptions.retryPolicy,
        shouldRetry = { exoPlayer.playWhenReady },
        transferListener = bandwidthMeter,
      ) { url ->
        browser?.getMediaRequestConfig(url)
      }

    exoPlayer =
      ExoPlayer.Builder(context)
        .setRenderersFactory(renderer)
        .setBandwidthMeter(bandwidthMeter)
        .setHandleAudioBecomingNoisy(setupOptions.handleAudioBecomingNoisy)
        .setMediaSourceFactory(mediaFactory)
        .setWakeMode(
          when (setupOptions.wakeMode) {
            AndroidPlayerWakeMode.NONE -> C.WAKE_MODE_NONE
            AndroidPlayerWakeMode.LOCAL -> C.WAKE_MODE_LOCAL
            AndroidPlayerWakeMode.NETWORK -> C.WAKE_MODE_NETWORK
          }
        )
        .setLoadControl(loadControl)
        .setName("AudioBrowser")
        .build()
    exoPlayer.setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(AndroidAudioContentTypeFactory.toMedia3(setupOptions.audioContentType))
        .build(),
      true, // handle audio focus
    )

    // Apply setup-specific options
    setupOptions.audioOffload?.let {
      val audioOffloadPreferences =
        TrackSelectionParameters.AudioOffloadPreferences.Builder()
          .setAudioOffloadMode(
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
          )
          .setIsGaplessSupportRequired(it.gaplessSupportRequired)
          .setIsSpeedChangeSupportRequired(it.rateChangeSupportRequired)
          .build()
      exoPlayer.trackSelectionParameters.audioOffloadPreferences = audioOffloadPreferences
    }

    // Recreate forwarding player with new ExoPlayer
    forwardingPlayer = InterceptingPlayer(exoPlayer)

    if (isInitialSetup) {
      // Initial setup - create player listener and emit initial state
      playerListener = PlayerListener(this)
      forwardingPlayer.addListener(playerListener)
      callbacks?.onPlaybackChanged(Playback(PlaybackState.NONE, null))

      // Initialize equalizer with audio session ID
      initializeEqualizer()
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
   * Starts observing network connectivity changes and invokes the callback when state changes.
   *
   * @param scope The coroutine scope to use for observation
   */
  fun observeNetworkConnectivity(scope: kotlinx.coroutines.CoroutineScope) {
    networkMonitor.observeOnline(scope) { isOnline -> callbacks?.onOnlineChanged(isOnline) }
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
   */
  fun setQueue(tracks: Array<Track>, startIndex: Int = 0, startPositionMs: Long = 0) {
    val mediaItems = TrackFactory.toMedia3(tracks).toMutableList()
    exoPlayer.setMediaItems(mediaItems, startIndex, startPositionMs)
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
    val index = exoPlayer.currentMediaItemIndex
    if (index == C.INDEX_UNSET) return

    val currentTrack = this.currentTrack ?: return

    // Update native favorites cache (only tracks with src can be favorited)
    currentTrack.src?.let { src -> browser?.browserManager?.updateFavorite(src, favorited) }

    // Create updated Track with new favorited state
    val updatedTrack =
      Track(
        url = currentTrack.url,
        src = currentTrack.src,
        artwork = currentTrack.artwork,
        title = currentTrack.title,
        subtitle = currentTrack.subtitle,
        artist = currentTrack.artist,
        album = currentTrack.album,
        description = currentTrack.description,
        genre = currentTrack.genre,
        duration = currentTrack.duration,
        style = currentTrack.style,
        childrenStyle = currentTrack.childrenStyle,
        favorited = favorited,
        groupTitle = currentTrack.groupTitle,
      )

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
   * Updates the now playing notification metadata. Pass null to clear overrides and revert to track
   * metadata.
   */
  fun updateNowPlaying(update: NowPlayingUpdate?) {
    nowPlayingOverride = update
    applyNowPlayingMetadata()
  }

  /** Gets the current now playing metadata (override if set, else track metadata). */
  fun getNowPlaying(): NowPlayingMetadata? {
    val track = currentTrack ?: return null
    val override = nowPlayingOverride

    return NowPlayingMetadata(
      elapsedTime = null,
      title = override?.title ?: track.title,
      album = track.album,
      artist = override?.artist ?: track.artist,
      duration = track.duration,
      artwork = track.artwork,
      description = track.description,
      mediaId = track.src ?: track.url,
      genre = track.genre,
      rating = null, // TODO: map track.favorited to rating if needed
    )
  }

  /**
   * Clears the now playing override when track changes. Called from
   * PlayerListener.onMediaItemTransition.
   */
  internal fun clearNowPlayingOverride() {
    nowPlayingOverride = null
  }

  /**
   * Applies the current now playing metadata to the media notification. Uses the override if set,
   * otherwise uses track metadata.
   */
  private fun applyNowPlayingMetadata() {
    val index = currentIndex ?: return
    val track = currentTrack ?: return
    val override = nowPlayingOverride

    val currentMediaItem = exoPlayer.getMediaItemAt(index)
    val updatedMetadata =
      currentMediaItem.mediaMetadata
        .buildUpon()
        .setTitle(override?.title ?: track.title)
        .setDisplayTitle(override?.title ?: track.title)
        .setArtist(override?.artist ?: track.artist)
        .build()

    val updatedMediaItem =
      currentMediaItem
        .buildUpon()
        .setUri(currentMediaItem.localConfiguration?.uri)
        .setMediaMetadata(updatedMetadata)
        .setTag(track)
        .build()

    exoPlayer.replaceMediaItem(index, updatedMediaItem)

    // Notify JS of the now playing metadata change
    getNowPlaying()?.let { callbacks?.onNowPlayingChanged(it) }
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
    exoPlayer.play()
    if (currentTrack != null) {
      exoPlayer.prepare()
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
      val browserManager = awaitBrowser().browserManager

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
    exoPlayer.pause()
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
    playbackState = PlaybackState.STOPPED
    exoPlayer.playWhenReady = false
    exoPlayer.stop()
  }

  fun clear() {
    exoPlayer.clearMediaItems()
  }

  /**
   * Stops and destroys the player. Only call this when you are finished using the player, otherwise
   * use [pause].
   */
  fun destroy() {
    stop()
    forwardingPlayer.removeListener(playerListener)
    automaticBufferManager?.detach()
    automaticBufferManager = null
    exoPlayer.release()
    cache?.release()
    cache = null
    networkMonitor.destroy()
    equalizerManager?.release()
    equalizerManager = null
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

      // Clear error when transitioning away from error state
      if (oldState == PlaybackState.ERROR) {
        playbackError = null
        callbacks?.onPlaybackError(null)
      }

      val playback = Playback(state, playbackError)
      callbacks?.onPlaybackChanged(playback)

      // Emit queue ended event when playback ends on the last track
      // This coupling ensures queue ended events are always triggered consistently with state
      // changes
      if (state == PlaybackState.ENDED && isLastTrack) {
        currentIndex?.let { index ->
          val event =
            PlaybackQueueEndedEvent(track = index.toDouble(), position = position.toSeconds())
          callbacks?.onPlaybackQueueEnded(event)
        }
        // Reset saved position to 0 so resumption starts from beginning (only when not repeating)
        if (repeatMode == RepeatMode.OFF) {
          playbackStateStore.savePositionZero()
        }
      }

      progressUpdateManager.onPlaybackStateChanged(state)
      val newPlayingState = PlayingStateFactory.derive(playWhenReady, state)
      if (newPlayingState != playingState) {
        Timber.d(
          "PlayingState changed: playing=${newPlayingState.playing}, buffering=${newPlayingState.buffering}"
        )
        playingState = newPlayingState
        callbacks?.onPlaybackPlayingState(playingState)
      }
    }
  }

  /**
   * Sets the progress update interval.
   *
   * @param interval The interval in seconds, or null to disable progress updates
   */
  fun setProgressUpdateInterval(interval: Double?) {
    progressUpdateManager.setUpdateInterval(interval)
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
    val ratingTypeChanged = previousOptions.ratingType != options.ratingType
    val shuffleChanged = previousOptions.shuffle != options.shuffle
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
        ratingTypeChanged ||
        shuffleChanged ||
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

    if (ratingTypeChanged) {
      options.ratingType?.let { ratingType = it }
    }

    if (shuffleChanged) {
      shuffleMode = options.shuffle
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

  /** Initializes the equalizer with the player's audio session ID. */
  private fun initializeEqualizer() {
    val audioSessionId = exoPlayer.audioSessionId
    if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
      Timber.d("Skipping equalizer init - no audio session yet, will init on first playback")
      return
    }

    try {
      equalizerManager =
        EqualizerManager(audioSessionId).apply {
          setOnSettingsChanged { settings -> callbacks?.onEqualizerChanged(settings) }
        }
      Timber.d("Equalizer initialized with session ID: $audioSessionId")
    } catch (e: Exception) {
      Timber.e(e, "Failed to initialize equalizer")
      equalizerManager = null
    }
  }

  /**
   * Reinitializes the equalizer when the audio session ID changes. Preserves current settings
   * (enabled state, preset, or custom levels). Also handles first-time initialization when the
   * equalizer was skipped at startup due to unset audio session ID.
   */
  internal fun reinitializeEqualizer(newAudioSessionId: Int) {
    val oldManager = equalizerManager

    // First-time initialization (skipped at startup because session was unset)
    if (oldManager == null) {
      Timber.d("First-time equalizer initialization with session ID: $newAudioSessionId")
      try {
        equalizerManager =
          EqualizerManager(newAudioSessionId).apply {
            setOnSettingsChanged { settings -> callbacks?.onEqualizerChanged(settings) }
          }
        Timber.d("Equalizer initialized with session ID: $newAudioSessionId")
      } catch (e: Exception) {
        Timber.e(e, "Failed to initialize equalizer")
        equalizerManager = null
      }
      return
    }

    // Same session ID, no need to reinitialize
    if (oldManager.audioSessionId == newAudioSessionId) {
      return
    }

    try {
      // Capture current settings before releasing old equalizer
      val currentSettings = oldManager.getSettings()

      // Release old equalizer
      oldManager.release()

      // Create new equalizer with new session ID
      equalizerManager =
        EqualizerManager(newAudioSessionId).apply {
          setOnSettingsChanged { settings -> callbacks?.onEqualizerChanged(settings) }
        }

      // Restore previous settings if available
      currentSettings?.let { settings ->
        if (settings.activePreset != null) {
          // Restore preset
          equalizerManager?.setPreset(settings.activePreset)
        } else if (settings.enabled) {
          // Restore custom levels
          equalizerManager?.setLevels(settings.bandLevels)
        }
        // Restore enabled state
        equalizerManager?.setEnabled(settings.enabled)
      }

      Timber.d("Equalizer reinitialized for new session ID: $newAudioSessionId")
    } catch (e: Exception) {
      Timber.e(e, "Failed to reinitialize equalizer")
      equalizerManager = null
    }
  }

  /**
   * Gets the current equalizer settings.
   *
   * @return Current equalizer settings or null if not available
   */
  fun getEqualizerSettings(): com.margelo.nitro.audiobrowser.EqualizerSettings? {
    return equalizerManager?.getSettings()
  }

  /**
   * Enables or disables the equalizer.
   *
   * @param enabled true to enable, false to disable
   */
  fun setEqualizerEnabled(enabled: Boolean) {
    equalizerManager?.setEnabled(enabled)
  }

  /**
   * Applies a preset to the equalizer.
   *
   * @param preset Name of the preset to apply
   */
  fun setEqualizerPreset(preset: String) {
    equalizerManager?.setPreset(preset)
  }

  /**
   * Sets custom band levels for the equalizer.
   *
   * @param levels Array of level values in millibels for each band
   */
  fun setEqualizerLevels(levels: DoubleArray) {
    equalizerManager?.setLevels(levels)
  }

  // MARK: - Sleep Timer

  /**
   * Gets the current sleep timer state.
   *
   * @return Sleep timer state or null if no timer is active
   */
  fun getSleepTimer(): NitroSleepTimer {
    return when {
      sleepTimer.time != null -> {
        NitroSleepTimer.create(SleepTimerTime(sleepTimer.time!!))
      }
      sleepTimer.sleepWhenPlayedToEnd -> {
        NitroSleepTimer.create(SleepTimerEndOfTrack(true))
      }
      else -> {
        NitroSleepTimer.create(NullType.NULL)
      }
    }
  }

  /**
   * Sets a sleep timer to stop playback after the specified duration.
   *
   * @param seconds Number of seconds until playback stops
   */
  fun setSleepTimer(seconds: Double) {
    sleepTimer.sleepAfter(seconds)
    callbacks?.onSleepTimerChanged(getSleepTimer())
  }

  /** Sets a sleep timer to stop playback when the current track finishes playing. */
  fun setSleepTimerToEndOfTrack() {
    sleepTimer.sleepWhenPlayedToEnd()
    callbacks?.onSleepTimerChanged(getSleepTimer())
  }

  /**
   * Clears the active sleep timer.
   *
   * @return true if a timer was cleared, false if no timer was active
   */
  fun clearSleepTimer(): Boolean {
    val wasRunning = sleepTimer.clear()
    if (wasRunning) {
      callbacks?.onSleepTimerChanged(NitroSleepTimer.create(NullType.NULL))
    }
    return wasRunning
  }

  /**
   * Checks if the sleep timer is set to end on track completion and stops playback if so. Called
   * when a track naturally finishes playing.
   */
  internal fun checkSleepTimerOnTrackEnd() {
    if (sleepTimer.sleepWhenPlayedToEnd) {
      Timber.d("Sleep timer triggered on track end, stopping playback")
      sleepTimer.clear()
      stop()
      callbacks?.onSleepTimerChanged(NitroSleepTimer.create(NullType.NULL))
    }
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
