package com.audiobrowser.player

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.audiobrowser.Callbacks
import com.audiobrowser.extension.NumberExt.Companion.toSeconds
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.util.AndroidAudioContentTypeFactory
import com.audiobrowser.util.PlayingStateFactory
import com.audiobrowser.util.RepeatModeFactory
import com.audiobrowser.util.TrackFactory
import com.google.common.util.concurrent.SettableFuture
import com.margelo.nitro.audiobrowser.AndroidPlayerWakeMode
import com.margelo.nitro.audiobrowser.AppKilledPlaybackBehavior
import com.margelo.nitro.audiobrowser.Playback
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
import com.margelo.nitro.audiobrowser.Track
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import timber.log.Timber

@SuppressLint("RestrictedApi")
class Player(internal val context: Context) {

  val appKilledPlaybackBehavior: AppKilledPlaybackBehavior
    get() = options.appKilledPlaybackBehavior

  private var options = PlayerUpdateOptions()
  internal var callbacks: Callbacks? = null
  private lateinit var mediaSession: MediaSession
  private val mediaSessionCallback = MediaSessionCallback(this)

  // Media browser functionality
  internal val pendingGetItemRequests = ConcurrentHashMap<String, SettableFuture<MediaItem?>>()
  internal val pendingGetChildrenRequests =
    ConcurrentHashMap<String, SettableFuture<List<MediaItem>>>()
  internal val pendingSearchRequests = ConcurrentHashMap<String, SettableFuture<List<MediaItem>>>()
  internal var mediaItemById: MutableMap<String, MediaItem> = mutableMapOf()

  lateinit var exoPlayer: ExoPlayer
  lateinit var forwardingPlayer: androidx.media3.common.Player

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
          LeastRecentlyUsedCacheEvictor(setupOptions.maxCacheSize.toLong() * 1000), // kb to bytes
          StandaloneDatabaseProvider(context),
        )
    } else {
      cache?.release()
      cache = null
    }

    // Recreate ExoPlayer with new setup options
    val renderer = DefaultRenderersFactory(context)
    renderer.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    val loadControl = run {
      val minBuffer = setupOptions.minBuffer.toInt()
      val maxBuffer = setupOptions.maxBuffer.toInt()
      val playBuffer = setupOptions.playBuffer.toInt()
      val defaultRebufferMultiplier = 2
      // DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / DEFAULT_BUFFER_FOR_PLAYBACK_MS
      val playAfterRebuffer =
        setupOptions.rebufferBuffer?.toInt() ?: (playBuffer * defaultRebufferMultiplier)
      val backBuffer = setupOptions.backBuffer.toInt()
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
    val audioAttributes =
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(AndroidAudioContentTypeFactory.toMedia3(setupOptions.audioContentType))
        .build()
    exoPlayer.setAudioAttributes(audioAttributes, true)

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
   * Replaces track at index in queue.
   *
   * @throws IllegalArgumentException if index is out of bounds.
   */
  fun replaceTrack(index: Int, track: Track) {
    validateIndex(index)
    exoPlayer.replaceMediaItem(index, TrackFactory.toMedia3(track))
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
  internal fun setPlaybackState(state: PlaybackState) {
    if (state != playbackState) {
      val oldState = playbackState
      playbackState = state

      // Clear error when transitioning away from error state
      if (oldState == PlaybackState.ERROR) {
        playbackError = null
        callbacks?.onPlaybackError(null)
      }

      val playbackState = Playback(state, playbackError)
      callbacks?.onPlaybackChanged(playbackState)

      // Emit queue ended event when playback ends on the last track
      // This coupling ensures queue ended events are always triggered consistently with state
      // changes
      if (state == PlaybackState.ENDED && isLastTrack) {
        currentIndex?.let { index ->
          val event =
            PlaybackQueueEndedEvent(track = index.toDouble(), position = position.toSeconds())
          callbacks?.onPlaybackQueueEnded(event)
        }
      }

      progressUpdateManager.onPlaybackStateChanged(state)
      val newPlayingState = PlayingStateFactory.derive(playWhenReady, state)
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
      mediaSessionCallback.updateMediaSession(
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
   * Resolves a pending GetItem request with the provided MediaItem.
   *
   * @param requestId The request ID to resolve
   * @param mediaItem The MediaItem to resolve with
   */
  fun resolveGetItemRequest(requestId: String, mediaItem: MediaItem) {
    // Store MediaItem in lookup map for later use in onAddMediaItems/onSetMediaItems
    mediaItem.mediaId.let { mediaId ->
      mediaItemById[mediaId] = mediaItem
      Timber.Forest.d(
        "Stored single MediaItem: mediaId=$mediaId, title=${mediaItem.mediaMetadata.title}"
      )
    }
    pendingGetItemRequests.remove(requestId)?.set(mediaItem)
  }

  /**
   * Resolves a pending GetChildren request with the provided list of MediaItems.
   *
   * @param requestId The request ID to resolve
   * @param items The list of MediaItems to resolve with
   * @param totalChildrenCount The total number of children (unused but maintained for
   *   compatibility)
   */
  fun resolveGetChildrenRequest(
    requestId: String,
    items: List<MediaItem>,
    totalChildrenCount: Int,
  ) {
    Timber.Forest.d("resolveGetChildrenRequest: requestId=$requestId, itemCount=${items.size}")
    // Store MediaItems in lookup map for later use in onAddMediaItems/onSetMediaItems
    items.forEach { mediaItem ->
      mediaItem.mediaId?.let { mediaId ->
        mediaItemById[mediaId] = mediaItem
        Timber.Forest.d(
          "Stored MediaItem: mediaId=$mediaId, title=${mediaItem.mediaMetadata.title}"
        )
      }
    }
    val future = pendingGetChildrenRequests.remove(requestId)
    if (future != null) {
      future.set(items)
      Timber.Forest.d("Resolved future for requestId=$requestId with ${items.size} items")
    } else {
      Timber.Forest.w("No pending future found for requestId=$requestId")
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
}
