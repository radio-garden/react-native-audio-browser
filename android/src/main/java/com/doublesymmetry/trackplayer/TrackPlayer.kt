package com.doublesymmetry.trackplayer

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.legacy.RatingCompat
import android.os.Bundle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Rating
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionError
import com.doublesymmetry.trackplayer.util.RepeatModeFactory
import com.doublesymmetry.trackplayer.util.RatingFactory
import com.margelo.nitro.audiobrowser.RemoteSetRatingEvent
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.margelo.nitro.audiobrowser.PlaybackActiveTrackChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackProgressUpdatedEvent
import com.margelo.nitro.audiobrowser.PlaybackQueueEndedEvent
import com.margelo.nitro.audiobrowser.RemoteJumpBackwardEvent
import com.margelo.nitro.audiobrowser.RemoteJumpForwardEvent
import com.margelo.nitro.audiobrowser.RemoteSeekEvent
import com.doublesymmetry.trackplayer.extension.NumberExt.Companion.toMilliseconds
import com.doublesymmetry.trackplayer.extension.NumberExt.Companion.toSeconds
import com.doublesymmetry.trackplayer.model.AudioOffloadOptions
import com.doublesymmetry.trackplayer.model.PlaybackMetadata
import com.doublesymmetry.trackplayer.model.PlayerSetupOptions
import com.doublesymmetry.trackplayer.model.PlayerUpdateOptions
import com.margelo.nitro.audiobrowser.RepeatMode
import com.doublesymmetry.trackplayer.player.MediaFactory
import com.doublesymmetry.trackplayer.player.PlaybackProgressUpdateManager
import com.doublesymmetry.trackplayer.player.PlayerListener
import com.margelo.nitro.audiobrowser.PlayingState as PlayingState
import com.doublesymmetry.trackplayer.util.MediaSessionManager
import com.doublesymmetry.trackplayer.util.MetadataAdapter
import com.doublesymmetry.trackplayer.util.PlayerCache
import com.doublesymmetry.trackplayer.util.TrackFactory
import com.margelo.nitro.audiobrowser.AppKilledPlaybackBehavior
import com.margelo.nitro.audiobrowser.PlaybackPlayWhenReadyChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.RatingType
import com.margelo.nitro.audiobrowser.State
import com.margelo.nitro.audiobrowser.Track
import java.util.concurrent.TimeUnit
import timber.log.Timber

@SuppressLint("RestrictedApi")
class TrackPlayer(
  internal val context: Context,
) {

  val appKilledPlaybackBehavior: AppKilledPlaybackBehavior
    get() = options.appKilledPlaybackBehavior
  private var options = PlayerUpdateOptions()
  private var callbacks: TrackPlayerCallbacks? = null
  private lateinit var mediaSession: MediaSession
  private val commandManager = MediaSessionManager()

  // Media browser functionality
  private val pendingGetItemRequests = ConcurrentHashMap<String, SettableFuture<MediaItem?>>()
  private val pendingGetChildrenRequests = ConcurrentHashMap<String, SettableFuture<List<MediaItem>>>()
  private val pendingSearchRequests = ConcurrentHashMap<String, SettableFuture<List<MediaItem>>>()
  private var mediaItemById: MutableMap<String, MediaItem> = mutableMapOf()

  lateinit var exoPlayer: ExoPlayer
  lateinit var forwardingPlayer: Player

  /**
   * ForwardingPlayer that intercepts external player actions and dispatches them to callbacks.
   *
   * This class blocks all external media item modifications to delegate control to RNTP. These
   * overrides prevent media controllers (like Android Auto, notifications) from directly modifying
   * the queue, ensuring all queue changes go through the RNTP API for proper state management and
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
      Timber.d("InterceptingPlayer.seekToNext() called")
      if (callbacks?.handleRemoteNext() != true) {
        super.seekToNext()
      }
    }

    override fun seekToNextMediaItem() {
      Timber.d("InterceptingPlayer.seekToNextMediaItem() called")
      if (callbacks?.handleRemoteNext() != true) {
        super.seekToNextMediaItem()
      }
    }

    override fun seekToPrevious() {
      Timber.d("InterceptingPlayer.seekToPrevious() called")
      if (callbacks?.handleRemotePrevious() != true) {
        super.seekToPrevious()
      }
    }

    override fun seekToPreviousMediaItem() {
      Timber.d("InterceptingPlayer.seekToPreviousMediaItem() called")
      if (callbacks?.handleRemotePrevious() != true) {
        super.seekToPreviousMediaItem()
      }
    }

    override fun seekForward() {
      Timber.d("InterceptingPlayer.seekForward() called")
      if (callbacks?.handleRemoteJumpForward(RemoteJumpForwardEvent(interval = options.forwardJumpInterval)) != true) {
        super.seekForward()
      }
    }

    override fun seekBack() {
      if (callbacks?.handleRemoteJumpBackward(RemoteJumpBackwardEvent(interval = options.backwardJumpInterval)) != true) {
        super.seekBack()
      }
    }

    override fun stop() {
      if (callbacks?.handleRemoteStop() != true) {
        super.stop()
      }
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
      if (callbacks?.handleRemoteSeek(RemoteSeekEvent(position = positionMs.toDouble() / 1000.0)) != true) {
        super.seekTo(mediaItemIndex, positionMs)
      }
    }

    override fun seekTo(positionMs: Long) {
      if (callbacks?.handleRemoteSeek(RemoteSeekEvent(position = positionMs.toDouble() / 1000.0)) != true) {
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
    get() = exoPlayer.currentMediaItem?.let { TrackFactory.media3ToBridge(it) }

  private var lastTrack: Track? = null
  private var lastIndex: Int? = null

  var playbackError: PlaybackError? = null
    internal set

  internal var playerState: State = State.NONE
    private set

  fun getPlaybackState(): PlaybackState {
    return PlaybackState(playerState, playbackError)
  }

  fun getPlayingState(): PlayingState {
    return playingState
  }

  internal fun emitActiveTrackChanged(lastPosition: Double) {
    val event =
      PlaybackActiveTrackChangedEvent(
        lastIndex = lastIndex?.toDouble(),
        lastTrack = lastTrack,
        lastPosition = lastPosition,
        index = currentIndex?.toDouble(),
        track = currentTrack,
      )
    callbacks?.onPlaybackActiveTrackChanged(event)

    // Update last track info for next transition
    lastTrack = currentTrack
    lastIndex = currentIndex
  }

  internal fun onTimedMetadata(metadata: Metadata) {
//    callbacks?.onMetadataTimedReceived(metadata)
//
    // Parse playback metadata from different formats
    val playbackMetadata =
      PlaybackMetadata.Companion.fromId3Metadata(metadata)
        ?: PlaybackMetadata.Companion.fromIcy(metadata)
        ?: PlaybackMetadata.Companion.fromVorbisComment(metadata)
        ?: PlaybackMetadata.Companion.fromQuickTime(metadata)

    playbackMetadata?.let {
      callbacks?.onPlaybackMetadata(it)
    }
  }

  internal fun onCommonMetadata(mediaMetadata: MediaMetadata) {
      callbacks?.onMetadataCommonReceived(MetadataAdapter.audioMetadataFromMediaMetadata(mediaMetadata))
  }

  internal fun onPlayWhenReadyChanged(playWhenReady: Boolean, pausedBecauseReachedEnd: Boolean) {
    callbacks?.onPlaybackPlayWhenReadyChanged(PlaybackPlayWhenReadyChangedEvent(playWhenReady))
    val newPlayingState = derivePlayingState(playWhenReady, playerState)
    if (newPlayingState != playingState) {
      playingState = newPlayingState
      callbacks?.onPlaybackPlayingState(playingState)
    }
  }

  internal fun onPlaybackError(playbackError: PlaybackError) {
    callbacks?.onPlaybackError(playbackError)
  }

  internal fun onSetRating(rating: Rating) {
    RatingFactory.media3ToBridge(rating)?.let {
      val event = RemoteSetRatingEvent(it)
      callbacks?.handleRemoteSetRating(event)
    }
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
    get() = if (exoPlayer.currentPosition == C.INDEX_UNSET.toLong()) 0 else exoPlayer.currentPosition

  val bufferedPosition: Long
    get() = if (exoPlayer.bufferedPosition == C.INDEX_UNSET.toLong()) 0 else exoPlayer.bufferedPosition

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
      val oldValue = repeatMode
      exoPlayer.repeatMode = RepeatModeFactory.toMedia3(value)

      // Emit event if value changed
      if (oldValue != value) {
        callbacks?.onPlaybackRepeatModeChanged(
        repeatMode
        )
      }
    }

  val currentIndex: Int?
    get() = if (exoPlayer.currentMediaItemIndex == C.INDEX_UNSET) null else exoPlayer.currentMediaItemIndex

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
    get() = (0 until exoPlayer.mediaItemCount).map { index ->
      TrackFactory.media3ToBridge(exoPlayer.getMediaItemAt(index))
    }.toTypedArray()

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
    return TrackFactory.media3ToBridge(exoPlayer.getMediaItemAt(index))
  }

  var skipSilence: Boolean
    get() = exoPlayer.skipSilenceEnabled
    internal set(value) {
      exoPlayer.skipSilenceEnabled = value
    }

  fun setAudioOffload(options: AudioOffloadOptions) {
    val audioOffloadPreferences =
      TrackSelectionParameters.AudioOffloadPreferences.Builder()
        .setAudioOffloadMode(
          TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        )
        .setIsGaplessSupportRequired(options.gaplessSupportRequired)
        .setIsSpeedChangeSupportRequired(options.rateChangeSupportRequired)
        .build()
    exoPlayer.trackSelectionParameters =
      exoPlayer.trackSelectionParameters
        .buildUpon()
        .setAudioOffloadPreferences(audioOffloadPreferences)
        .build()
  }


  /**
   * Sets up or recreates the ExoPlayer with the provided setup options.
   * This method can be called multiple times to change setup options.
   */
  fun setup(setupOptions: PlayerSetupOptions) {
    Timber.d("Setting up player with new options")

    val isInitialSetup = !::exoPlayer.isInitialized

    if (!isInitialSetup) {
      forwardingPlayer.removeListener(playerListener)
      exoPlayer.release()
      Timber.d("Player cleanup completed")
    }

    // Update cache if needed
    if (setupOptions.maxCacheSize > 0) {
      cache = PlayerCache.initCache(context, setupOptions.maxCacheSize.toLong())
    } else {
      // Release existing cache if maxCacheSize is 0
      cache?.release()
      cache = null
    }

    // Recreate ExoPlayer with new setup options
    val renderer = DefaultRenderersFactory(context)
    renderer.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    val loadControl = run {
      val multiplier =
        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS.toDouble() /
          DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS.toDouble()
      val minBuffer =
        setupOptions.minBuffer?.toMilliseconds()?.toInt()?.takeIf { it != 0 }
          ?: DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
      val maxBuffer =
        setupOptions.maxBuffer?.toMilliseconds()?.toInt()?.takeIf { it != 0 }
          ?: DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
      val playBuffer =
        setupOptions.playBuffer?.toMilliseconds()?.toInt()?.takeIf { it != 0 }
          ?: DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
      val playAfterRebuffer =
        setupOptions.rebufferBuffer?.toMilliseconds()?.toInt()?.takeIf { it != 0 }
          ?: (playBuffer * multiplier).toInt()
      val backBuffer =
        setupOptions.backBuffer?.toMilliseconds()?.toInt()?.takeIf { it != 0 }
          ?: DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS
      DefaultLoadControl.Builder()
        .setBufferDurationsMs(minBuffer, maxBuffer, playBuffer, playAfterRebuffer)
        .setBackBuffer(backBuffer, false)
        .build()
    }
    exoPlayer =
      ExoPlayer.Builder(context)
        .setRenderersFactory(renderer)
        .setHandleAudioBecomingNoisy(setupOptions.handleAudioBecomingNoisy)
        .setMediaSourceFactory(MediaFactory(context, cache))
        .setWakeMode(setupOptions.wakeMode.toMedia3())
        .setLoadControl(loadControl)
        .setName("rntp")
        .build()
    val audioAttributes =
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(setupOptions.audioContentType.toMedia3())
        .build()
    exoPlayer.setAudioAttributes(audioAttributes, true)

    // Apply setup-specific options
    setupOptions.audioOffload?.let { setAudioOffload(it) }

    // Recreate forwarding player with new ExoPlayer
    forwardingPlayer = InterceptingPlayer(exoPlayer)

    if (isInitialSetup) {
      // Initial setup - create player listener and emit initial state
      playerListener = PlayerListener(this)
      forwardingPlayer.addListener(playerListener)
      callbacks?.onPlaybackState(PlaybackState(State.NONE, null))
    } else {
      // Re-setup - re-add listener and update MediaSession
      forwardingPlayer.addListener(playerListener)

      // Update MediaSession with new forwardingPlayer reference if MediaSession exists
      if (::mediaSession.isInitialized) {
        Timber.d("Updating MediaSession with new forwardingPlayer reference")
        mediaSession.player = forwardingPlayer
      }

      setPlayerState(State.NONE)
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
    val mediaItem = TrackFactory.bridgeToMedia3(track)
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
    val mediaItems = TrackFactory.bridgeToMedia3(tracks)
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
    val mediaItems = tracks.map { TrackFactory.bridgeToMedia3(it) }
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
   * Replaces track at index in queue.
   *
   * @throws IllegalArgumentException if index is out of bounds.
   */
  fun replaceTrack(index: Int, track: Track) {
    validateIndex(index)
    exoPlayer.replaceMediaItem(index, TrackFactory.bridgeToMedia3(track))
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
    playerState = State.STOPPED
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
    exoPlayer.release()
    cache?.release()
    cache = null
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
  internal fun setPlayerState(state: State) {
    if (state != playerState) {
      val oldState = playerState
      playerState = state

      // Clear error when transitioning away from error state
      if (oldState == State.ERROR) {
        playbackError = null
        callbacks?.onPlaybackError(null)
      }

      val playbackState = PlaybackState(state, playbackError)
      callbacks?.onPlaybackState(playbackState)

      // Emit queue ended event when playback ends on the last track
      // This coupling ensures queue ended events are always triggered consistently with state
      // changes
      if (state == State.ENDED && isLastTrack) {
        currentIndex?.let { index ->
          val event = PlaybackQueueEndedEvent(track = index.toDouble(), position = position.toSeconds())
          callbacks?.onPlaybackQueueEnded(event)
        }
      }

      progressUpdateManager.onPlaybackStateChanged(state)
      val newPlayingState = derivePlayingState(playWhenReady, state)
      if (newPlayingState != playingState) {
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
   * Applies update options with change detection.
   * Only updates properties that have actually changed and emits events accordingly.
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
    val notificationCapabilitiesChanged =
      previousOptions.notificationCapabilities != options.notificationCapabilities
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
        notificationCapabilitiesChanged ||
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

    if (capabilitiesChanged || notificationCapabilitiesChanged) {
      commandManager.updateMediaSession(
        mediaSession,
        options.capabilities,
        options.notificationCapabilities,
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
  fun setCallbacks(callbacks: TrackPlayerCallbacks?) {
    this.callbacks = callbacks
  }

  /**
   * Gets the current callbacks instance.
   *
   * @return The current callbacks, or null if none are set
   */
  fun getCallbacks(): TrackPlayerCallbacks? {
    return this.callbacks
  }

  fun setMediaSession(mediaSession: androidx.media3.session.MediaSession) {
    this.mediaSession = mediaSession
  }

  fun getOptions(): PlayerUpdateOptions {
    return options
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
   * Gets the MediaSessionCallback for this TrackPlayer.
   *
   * @return MediaLibrarySession.Callback instance
   */
  fun getMediaSessionCallback(): MediaLibraryService.MediaLibrarySession.Callback {
    return MediaSessionCallback()
  }

  /**
   * MediaLibrarySession callback that handles all media session interactions.
   * All logic is handled directly by the TrackPlayer.
   */
  private inner class MediaSessionCallback : MediaLibraryService.MediaLibrarySession.Callback {
    override fun onConnect(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
      Timber.d("MediaSession connect: ${controller.packageName}")
      return commandManager.buildConnectionResult(session)
    }

    override fun onCustomCommand(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      command: SessionCommand,
      args: Bundle,
    ): ListenableFuture<SessionResult> {
      commandManager.handleCustomCommand(command, this@TrackPlayer)
      return super.onCustomCommand(session, controller, command, args)
    }

    override fun onSetRating(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      rating: Rating,
    ): ListenableFuture<SessionResult> {
      onSetRating(rating)
      return super.onSetRating(session, controller, rating)
    }

    override fun onGetLibraryRoot(
      session: MediaLibraryService.MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
      Timber.d("onGetLibraryRoot: { package: ${browser.packageName} }")
      return Futures.immediateFuture(
        LibraryResult.ofItem(
          MediaItem.Builder()
            .setMediaId("__ROOT__")
            .setMediaMetadata(
              MediaMetadata.Builder()
                .setTitle("Root")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
            )
            .build(),
          null
        )
      )
    }

    override fun onGetChildren(
      session: MediaLibraryService.MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      parentId: String,
      page: Int,
      pageSize: Int,
      params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
      Timber.d("onGetChildren: {parentId: $parentId, page: $page, pageSize: $pageSize }")
      val requestId = UUID.randomUUID().toString()
      val future = SettableFuture.create<List<MediaItem>>()
      // Store the future for later resolution
      pendingGetChildrenRequests[requestId] = future
      // Emit event to JavaScript via callbacks
      callbacks?.onGetChildrenRequest(requestId, parentId, page, pageSize)
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
      Timber.d("onGetItem: ${browser.packageName}, mediaId = $mediaId")
      val requestId = UUID.randomUUID().toString()
      val future = SettableFuture.create<MediaItem?>()
      // Store the future for later resolution
      pendingGetItemRequests[requestId] = future
      // Emit event to JavaScript via callbacks
      callbacks?.onGetItemRequest(requestId, mediaId)
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
      params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
      Timber.d("onSearch: ${browser.packageName}, query = $query")
      // Emit event to JavaScript via callbacks for search initiation
      val requestId = UUID.randomUUID().toString()
      val extrasMap = params?.extras?.let { bundle ->
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
      callbacks?.onSearchRequest(requestId, query, extrasMap)
      return super.onSearch(session, browser, query, params)
    }

    override fun onSetMediaItems(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
      mediaItems: MutableList<MediaItem>,
      startIndex: Int,
      startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
      Timber.d(
        "onSetMediaItems: ${controller.packageName}, mediaId=${mediaItems[0].mediaId}, uri=${mediaItems[0].localConfiguration?.uri}, title=${mediaItems[0].mediaMetadata.title}"
      )

      val resolvedItems = mediaItems.map { mediaItem ->
        val mediaId = mediaItem.mediaId
        val fullMediaItem = mediaItemById[mediaId]
        if (fullMediaItem != null) {
          Timber.d("Found full MediaItem for mediaId: $mediaId")
          fullMediaItem
        } else {
          Timber.d("No full MediaItem found for mediaId: $mediaId, using original")
          mediaItem
        }
      }

      try {
        clear()
        add(TrackFactory.media3ToBridge(resolvedItems))
        skipTo(startIndex)
        seekTo(startPositionMs, TimeUnit.MILLISECONDS)
        play()
      } catch (e: Exception) {
        Timber.e(e, "Error in onSetMediaItems")
      }

      return Futures.immediateFuture(
        MediaSession.MediaItemsWithStartPosition(
          resolvedItems,
          startIndex,
          startPositionMs,
        )
      )
    }

  }

  /**
   * Resolves a pending GetItem request with the provided MediaItem.
   *
   * @param requestId The request ID to resolve
   * @param mediaItem The MediaItem to resolve with
   */
  fun resolveGetItemRequest(requestId: String, mediaItem: MediaItem) {
    // Store MediaItem in lookup map for later use in onAddMediaItems/onSetMediaItems
    mediaItem.mediaId.let { mediaId ->
      mediaItemById[mediaId] = mediaItem
      Timber.d("Stored single MediaItem: mediaId=$mediaId, title=${mediaItem.mediaMetadata.title}")
    }
    pendingGetItemRequests.remove(requestId)?.set(mediaItem)
  }

  /**
   * Resolves a pending GetChildren request with the provided list of MediaItems.
   *
   * @param requestId The request ID to resolve
   * @param items The list of MediaItems to resolve with
   * @param totalChildrenCount The total number of children (unused but maintained for compatibility)
   */
  fun resolveGetChildrenRequest(
    requestId: String,
    items: List<MediaItem>,
    totalChildrenCount: Int,
  ) {
    Timber.d(
      "resolveGetChildrenRequest: requestId=$requestId, itemCount=${items.size}"
    )
    // Store MediaItems in lookup map for later use in onAddMediaItems/onSetMediaItems
    items.forEach { mediaItem ->
      mediaItem.mediaId?.let { mediaId ->
        mediaItemById[mediaId] = mediaItem
        Timber.d("Stored MediaItem: mediaId=$mediaId, title=${mediaItem.mediaMetadata.title}")
      }
    }
    val future = pendingGetChildrenRequests.remove(requestId)
    if (future != null) {
      future.set(items)
      Timber.d("Resolved future for requestId=$requestId with ${items.size} items")
    } else {
      Timber.w("No pending future found for requestId=$requestId")
    }
  }

  /**
   * Resolves a pending Search request with the provided list of MediaItems.
   *
   * @param requestId The request ID to resolve
   * @param items The list of MediaItems to resolve with
   * @param totalMatchesCount The total number of matches (unused but maintained for compatibility)
   */
  fun resolveSearchRequest(requestId: String, items: List<MediaItem>, totalMatchesCount: Int) {
    pendingSearchRequests.remove(requestId)?.set(items)
  }

  /**
   * Derives a PlayingState from playWhenReady and state.
   *
   * @param playWhenReady Whether the player wants to play when ready
   * @param state The current player state
   * @return A PlayingState representing the current playing/buffering status
   */
  private fun derivePlayingState(playWhenReady: Boolean, state: State): PlayingState {
    val playing = playWhenReady && !(state == State.ERROR || state == State.ENDED || state == State.NONE)
    val buffering = playWhenReady && (state == State.LOADING || state == State.BUFFERING)
    return PlayingState(playing, buffering)
  }
}
