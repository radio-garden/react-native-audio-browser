package com.audiobrowser

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.legacy.RatingCompat
import com.audiobrowser.event.ControllerConnectedEvent
import com.audiobrowser.event.ControllerDisconnectedEvent
import com.audiobrowser.event.PlaybackActiveTrackChangedEvent
import com.audiobrowser.event.PlaybackError
import com.audiobrowser.event.PlaybackErrorEvent
import com.audiobrowser.event.PlaybackPlayWhenReadyChangedEvent
import com.audiobrowser.event.PlaybackPlayingStateEvent
import com.audiobrowser.event.PlaybackProgressUpdatedEvent
import com.audiobrowser.event.PlaybackQueueEndedEvent
import com.audiobrowser.event.RemoteJumpBackwardEvent
import com.audiobrowser.event.RemoteJumpForwardEvent
import com.audiobrowser.event.RemoteSeekEvent
import com.audiobrowser.event.RemoteSetRatingEvent
import com.audiobrowser.extension.NumberExt.Companion.toSeconds
import com.audiobrowser.model.PlaybackMetadata
import com.audiobrowser.model.PlaybackState
import com.audiobrowser.model.RatingType
import com.audiobrowser.model.State
import com.audiobrowser.model.Track
import com.audiobrowser.option.PlayerOptions
import com.audiobrowser.option.PlayerRepeatMode
import com.audiobrowser.player.MediaFactory
import com.audiobrowser.player.PlaybackProgressUpdateManager
import com.audiobrowser.player.PlayerListener
import com.audiobrowser.player.PlayingState
import com.audiobrowser.util.MetadataAdapter
import com.audiobrowser.util.PlayerCache
import com.facebook.react.bridge.WritableMap
import java.util.concurrent.TimeUnit

@UnstableApi
class AudioPlayer(
  internal val context: Context,
  val options: PlayerOptions = PlayerOptions(),
  callbacks: AudioPlayerCallbacks? = null,
) {

  private var callbacks: AudioPlayerCallbacks? = callbacks
  val exoPlayer: ExoPlayer
  val forwardingPlayer: Player
  val player: Player
    get() {
      return options.interceptPlayerActionsTriggeredExternally
        .takeIf { it }
        ?.let { forwardingPlayer } ?: exoPlayer
    }

  /**
   * ForwardingPlayer that intercepts external player actions and dispatches them to callbacks.
   *
   * This class blocks all external media item modifications to delegate control to RNTP. These
   * overrides prevent media controllers (like Android Auto, notifications) from directly modifying
   * the queue, ensuring all queue changes go through the RNTP API for proper state management and
   * event handling.
   */
  @UnstableApi
  private inner class InterceptingPlayer(player: ExoPlayer) : ForwardingPlayer(player) {

    // Block all external media item modifications
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
      return
    }

    override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
      return
    }

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
      return
    }

    override fun setMediaItems(
      mediaItems: MutableList<MediaItem>,
      startIndex: Int,
      startPositionMs: Long,
    ) {
      return
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
      return
    }

    // Intercept playback controls and dispatch to callbacks or fall back to default behavior
    override fun play() {
      callbacks?.onRemotePlay() ?: super.play()
    }

    override fun pause() {
      callbacks?.onRemotePause() ?: super.pause()
    }

    override fun seekToNext() {
      callbacks?.onRemoteNext() ?: super.seekToNext()
    }

    override fun seekToNextMediaItem() {
      callbacks?.onRemoteNext() ?: super.seekToNextMediaItem()
    }

    override fun seekToPrevious() {
      callbacks?.onRemotePrevious() ?: super.seekToPrevious()
    }

    override fun seekToPreviousMediaItem() {
      callbacks?.onRemotePrevious() ?: super.seekToPreviousMediaItem()
    }

    override fun seekForward() {
      callbacks?.let {
        it.onRemoteJumpForward(
          RemoteJumpForwardEvent(interval = options.forwardJumpInterval.toDouble())
        )
      } ?: super.seekForward()
    }

    override fun seekBack() {
      callbacks?.let {
        it.onRemoteJumpBackward(
          RemoteJumpBackwardEvent(interval = options.backwardJumpInterval.toDouble())
        )
      } ?: super.seekBack()
    }

    override fun stop() {
      callbacks?.onRemoteStop() ?: super.stop()
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
      callbacks?.let { it.onRemoteSeek(RemoteSeekEvent(position = positionMs.toDouble() / 1000.0)) }
        ?: super.seekTo(mediaItemIndex, positionMs)
    }

    override fun seekTo(positionMs: Long) {
      callbacks?.let { it.onRemoteSeek(RemoteSeekEvent(position = positionMs.toDouble() / 1000.0)) }
        ?: super.seekTo(positionMs)
    }
  }

  private var playerListener: PlayerListener
  private var cache: SimpleCache? = null

  private val progressUpdateManager: PlaybackProgressUpdateManager by lazy {
    PlaybackProgressUpdateManager {
      val index = currentIndex ?: return@PlaybackProgressUpdateManager
      val event =
        PlaybackProgressUpdatedEvent(
          position = position.toSeconds(),
          duration = duration.toSeconds(),
          buffered = bufferedPosition.toSeconds(),
          track = index,
        )
      callbacks?.onPlaybackProgressUpdated(event)
    }
  }

  internal val playingState: PlayingState by lazy {
    PlayingState { event -> callbacks?.onPlaybackPlayingState(event) }
  }

  val currentTrack: Track?
    get() = exoPlayer.currentMediaItem?.let { Track.fromMediaItem(it, context, ratingType) }

  private var lastTrack: Track? = null
  private var lastIndex: Int? = null

  var playbackError: PlaybackError? = null
    internal set

  internal var playerState: State = State.NONE
    private set

  fun getPlaybackState(): PlaybackState {
    return PlaybackState(playerState, playbackError)
  }

  fun getPlayingState(): PlaybackPlayingStateEvent {
    return playingState.toEvent()
  }

  internal fun emitActiveTrackChanged(lastPosition: Double) {
    val event =
      PlaybackActiveTrackChangedEvent(
        lastIndex = lastIndex,
        lastTrack = lastTrack,
        lastPosition = lastPosition,
        index = currentIndex,
        track = currentTrack,
      )
    callbacks?.onPlaybackActiveTrackChanged(event)

    // Update last track info for next transition
    lastTrack = currentTrack
    lastIndex = currentIndex
  }

  internal fun onTimedMetadata(metadata: androidx.media3.common.Metadata) {
    callbacks?.onMetadataTimedReceived(metadata)

    // Parse playback metadata from different formats
    val playbackMetadata =
      PlaybackMetadata.Companion.fromId3Metadata(metadata)
        ?: PlaybackMetadata.Companion.fromIcy(metadata)
        ?: PlaybackMetadata.Companion.fromVorbisComment(metadata)
        ?: PlaybackMetadata.Companion.fromQuickTime(metadata)

    callbacks?.onPlaybackMetadata(playbackMetadata)
  }

  internal fun onCommonMetadata(mediaMetadata: androidx.media3.common.MediaMetadata) {
    val metadata = MetadataAdapter.Companion.mapFromMediaMetadata(mediaMetadata)
    // Safe cast: Arguments.createMap() returns WritableMap which extends ReadableMap
    (metadata as? WritableMap)?.let { callbacks?.onMetadataCommonReceived(it) }
  }

  internal fun onPlayWhenReadyChanged(playWhenReady: Boolean, pausedBecauseReachedEnd: Boolean) {
    callbacks?.onPlaybackPlayWhenReadyChanged(PlaybackPlayWhenReadyChangedEvent(playWhenReady))
    playingState.update(playWhenReady, playerState)
  }

  internal fun onPlaybackError(playbackError: PlaybackError) {
    val event =
      PlaybackErrorEvent(
        code = playbackError.code ?: "UNKNOWN_ERROR",
        message = playbackError.message ?: "An unknown error occurred",
      )
    callbacks?.onPlaybackError(event)
  }

  internal fun onControllerConnected(
    controllerData: com.audiobrowser.event.EventControllerConnection
  ) {
    val event =
      ControllerConnectedEvent(
        `package` = controllerData.packageName,
        isMediaNotificationController = controllerData.isMediaNotificationController,
        isAutomotiveController = controllerData.isAutomotiveController,
        isAutoCompanionController = controllerData.isAutoCompanionController,
      )
    callbacks?.onControllerConnected(event)
  }

  internal fun onControllerDisconnected(packageName: String) {
    callbacks?.onControllerDisconnected(ControllerDisconnectedEvent(`package` = packageName))
  }

  internal fun onRatingChanged(rating: Any) {
    if (rating is androidx.media3.common.Rating) {
      RatingType.fromString(rating.toString())?.let { ratingType ->
        val event = RemoteSetRatingEvent(rating = ratingType)
        callbacks?.onRemoteSetRating(event)
      } ?: timber.log.Timber.w("Failed to convert rating: $rating")
    }
  }

  var playWhenReady: Boolean
    get() = exoPlayer.playWhenReady
    set(value) {
      exoPlayer.playWhenReady = value
    }

  val duration: Long
    get() {
      return if (exoPlayer.duration == C.TIME_UNSET) 0 else exoPlayer.duration
    }

  internal var oldPosition = 0L

  val position: Long
    get() {
      return if (exoPlayer.currentPosition == C.INDEX_UNSET.toLong()) 0
      else exoPlayer.currentPosition
    }

  val bufferedPosition: Long
    get() {
      return if (exoPlayer.bufferedPosition == C.INDEX_UNSET.toLong()) 0
      else exoPlayer.bufferedPosition
    }

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

  var ratingType: Int = RatingCompat.RATING_NONE

  var repeatMode: PlayerRepeatMode
    get() {
      return when (exoPlayer.repeatMode) {
        Player.REPEAT_MODE_ALL -> PlayerRepeatMode.ALL
        Player.REPEAT_MODE_ONE -> PlayerRepeatMode.ONE
        else -> PlayerRepeatMode.OFF
      }
    }
    set(value) {
      when (value) {
        PlayerRepeatMode.ALL -> exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
        PlayerRepeatMode.ONE -> exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
        PlayerRepeatMode.OFF -> exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
      }
    }

  val currentIndex: Int?
    get() =
      if (exoPlayer.currentMediaItemIndex == C.INDEX_UNSET) null
      else exoPlayer.currentMediaItemIndex

  var shuffleMode
    get() = exoPlayer.shuffleModeEnabled
    set(v) {
      exoPlayer.shuffleModeEnabled = v
    }

  val trackCount: Int
    get() = exoPlayer.mediaItemCount

  val isEmpty: Boolean
    get() = exoPlayer.mediaItemCount == 0

  val tracks: List<Track>
    get() =
      (0 until exoPlayer.mediaItemCount).map { index ->
        Track.fromMediaItem(exoPlayer.getMediaItemAt(index), context, ratingType)
      }

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
    return Track.fromMediaItem(exoPlayer.getMediaItemAt(index), context, ratingType)
  }

  var skipSilence: Boolean
    get() = exoPlayer.skipSilenceEnabled
    set(value) {
      exoPlayer.skipSilenceEnabled = value
    }

  fun setAudioOffload(offload: Boolean = true) {
    val audioOffloadPreferences =
      TrackSelectionParameters.AudioOffloadPreferences.Builder()
        .setAudioOffloadMode(
          if (offload) TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
          else TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        )
        // Add additional options as needed
        .setIsGaplessSupportRequired(true)
        .setIsSpeedChangeSupportRequired(true)
        .build()
    exoPlayer.trackSelectionParameters =
      exoPlayer.trackSelectionParameters
        .buildUpon()
        .setAudioOffloadPreferences(audioOffloadPreferences)
        .build()
  }

  init {
    if (options.cacheSizeKb > 0) {
      cache = PlayerCache.initCache(context, options.cacheSizeKb)
    }
    callbacks?.onPlaybackState(PlaybackState(State.NONE))

    val renderer = DefaultRenderersFactory(context)
    renderer.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

    val loadControl = run {
      val bufferConfig = options.bufferOptions
      val multiplier =
        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS.toDouble() /
          DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS.toDouble()
      val minBuffer =
        bufferConfig.minBuffer?.takeIf { it != 0 } ?: DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
      val maxBuffer =
        bufferConfig.maxBuffer?.takeIf { it != 0 } ?: DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
      val playBuffer =
        bufferConfig.playBuffer?.takeIf { it != 0 }
          ?: DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
      val playAfterRebuffer =
        bufferConfig.rebufferBuffer?.takeIf { it != 0 } ?: (playBuffer * multiplier).toInt()
      val backBuffer =
        bufferConfig.backBuffer?.takeIf { it != 0 }
          ?: DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS
      DefaultLoadControl.Builder()
        .setBufferDurationsMs(minBuffer, maxBuffer, playBuffer, playAfterRebuffer)
        .setBackBuffer(backBuffer, false)
        .build()
    }

    exoPlayer =
      ExoPlayer.Builder(context)
        .setRenderersFactory(renderer)
        .setHandleAudioBecomingNoisy(options.handleAudioBecomingNoisy)
        .setMediaSourceFactory(MediaFactory(context, cache))
        .setWakeMode(options.wakeMode.toExoPlayer())
        .setLoadControl(loadControl)
        .setSkipSilenceEnabled(options.skipSilence)
        .setName("kotlin-audio-player")
        .build()

    val audioAttributes =
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(options.audioContentType.toExoPlayer())
        .build()
    exoPlayer.setAudioAttributes(audioAttributes, true)
    forwardingPlayer = InterceptingPlayer(exoPlayer)
    playerListener = PlayerListener(this)
    player.addListener(playerListener)
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
    val mediaSource = track.toMediaItem()
    exoPlayer.addMediaItem(mediaSource)
    exoPlayer.prepare()
  }

  /**
   * Add multiple tracks to the queue. If the AudioPlayer has no track loaded, it will load the
   * first track in the list.
   *
   * @param tracks The [Track]s to add.
   */
  fun add(tracks: List<Track>) {
    val mediaItems = tracks.map { it.toMediaItem() }
    exoPlayer.addMediaItems(mediaItems)
    exoPlayer.prepare()
  }

  /**
   * Add multiple tracks to the queue.
   *
   * @param tracks The [Track]s to add.
   * @param atIndex Index to insert tracks at. Use -1 to append to the end of the queue.
   * @throws IllegalArgumentException if index is out of bounds.
   */
  fun add(tracks: List<Track>, atIndex: Int) {
    validateInsertIndex(atIndex)
    val index = if (atIndex == -1) exoPlayer.mediaItemCount else atIndex
    val mediaItems = tracks.map { it.toMediaItem() }
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
    val mediaItem = track.toMediaItem()
    exoPlayer.replaceMediaItem(index, mediaItem)
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
    timber.log.Timber.d(
      "TrackPlayer.play() called, callbacks=${if (callbacks != null) "SET" else "NULL"}"
    )
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
    player.removeListener(playerListener)
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
      if (oldState == State.ERROR && state != State.ERROR) {
        playbackError = null
        callbacks?.onPlaybackError(PlaybackErrorEvent())
      }

      val playbackState = PlaybackState(state, playbackError)
      timber.log.Timber.d(
        "TrackPlayer firing onPlaybackState: state=$state, callbacks=${if (callbacks != null) "SET" else "NULL"}"
      )
      callbacks?.onPlaybackState(playbackState)

      // Emit queue ended event when playback ends on the last track
      // This coupling ensures queue ended events are always triggered consistently with state
      // changes
      if (state == State.ENDED && isLastTrack) {
        currentIndex?.let { index ->
          val event = PlaybackQueueEndedEvent(track = index, position = position.toSeconds())
          callbacks?.onPlaybackQueueEnded(event)
        }
      }

      progressUpdateManager.onPlaybackStateChanged(state)
      playingState.update(playWhenReady, state)
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
   * Sets the callbacks for player events.
   *
   * @param callbacks The callbacks to set, or null to clear callbacks
   */
  fun setCallbacks(callbacks: AudioPlayerCallbacks?) {
    this.callbacks = callbacks
  }

  /**
   * Gets the current callbacks instance.
   *
   * @return The current callbacks, or null if none are set
   */
  fun getCallbacks(): AudioPlayerCallbacks? {
    return this.callbacks
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
}
