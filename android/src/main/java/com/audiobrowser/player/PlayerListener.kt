package com.audiobrowser.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.Timeline
import androidx.media3.common.util.StuckPlayerException
import com.audiobrowser.extension.NumberExt.Companion.toSeconds
import com.audiobrowser.model.PlaybackMetadata
import com.audiobrowser.util.MetadataAdapter
import com.audiobrowser.util.RepeatModeFactory
import com.margelo.nitro.audiobrowser.PlaybackActiveTrackChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackErrorKind
import com.margelo.nitro.audiobrowser.PlaybackState
import java.util.Locale
import timber.log.Timber

class PlayerListener(private val player: Player) : MediaPlayer.Listener {
  // Main looper: Player.Listener callbacks arrive on the main thread.
  private val mainHandler = Handler(Looper.getMainLooper())

  /** Whether the phone's engine played at the last event, so a hand-over starts a new Load. */
  private var localActive = true

  @MediaPlayer.State private var lastEngineState = MediaPlayer.STATE_IDLE

  /**
   * Set from an app-started command that ends the stream until the playback thread has handled it.
   * Until then every metadata delivery is the outgoing stream's: its renderer posted them to the
   * main looper before the command, and nothing removes them.
   */
  private var metadataFence: Any? = null

  /**
   * Call right after the command. Its target runs on the playback thread right after that command.
   * A main-looper target goes to the back of that thread's queue, where a release would drop it.
   */
  internal fun fenceOutgoingMetadata() {
    val fence = Any()
    metadataFence = fence
    player.exoPlayer
      .createMessage { _, _ ->
        mainHandler.post { if (metadataFence === fence) metadataFence = null }
      }
      .send()
  }

  override fun onMetadata(metadata: Metadata) {
    if (metadataFence != null) return
    val chapters = MetadataAdapter.extractChapters(metadata)
    if (chapters.isNotEmpty()) {
      player.callbacks?.onChapterMetadata(chapters)
    }

    PlaybackMetadata.from(metadata, player.currentTrack?.icyCharset)?.let {
      val timed = it.toNitro()
      player.callbacks?.onTimedMetadata(timed)
      player.nowPlaying.onTimedMetadataReceived(timed)
    }
  }

  override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
    player.callbacks?.onTrackMetadata(
      MetadataAdapter.Companion.trackMetadataFromMediaMetadata(mediaMetadata)
    )
  }

  override fun onPositionDiscontinuity(
    oldPosition: MediaPlayer.PositionInfo,
    newPosition: MediaPlayer.PositionInfo,
    reason: Int,
  ) {
    player.oldPosition = oldPosition.positionMs
    // A seek opens a new connection without passing through idle, clearing a failed one's error.
    val loop = player.retryLoop
    if (
      reason == MediaPlayer.DISCONTINUITY_REASON_SEEK &&
        loop?.failurePending == true &&
        player.localEngine.isActive
    ) {
      player.clearRetryingError()
      loop.connectionStarted()
    }
  }

  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    val lastPosition = player.oldPosition.toSeconds()
    val event =
      PlaybackActiveTrackChangedEvent(
        lastIndex = player.lastIndex?.toDouble(),
        lastTrack = player.lastTrack,
        lastPosition = lastPosition,
        index = player.currentIndex?.toDouble(),
        track = player.currentTrack,
      )
    val ended =
      reason == MediaPlayer.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
        reason == MediaPlayer.MEDIA_ITEM_TRANSITION_REASON_REPEAT
    player.nowPlaying.clearOverride()
    if (!ended) fenceOutgoingMetadata()
    player.callbacks?.onPlaybackActiveTrackChanged(event)

    if (ended) player.sleepTimer.onTrackEnd()

    player.lastTrack = player.currentTrack
    player.lastIndex = player.currentIndex

    player.updateFavoriteButtonState(player.currentTrack?.favorited)
    player.updateLiveState(player.currentTrack?.live == true)

    // The browse MediaItem's artist is the list subtitle; render() restamps the playing item with
    // the canonical artist for the now-playing surfaces (Android Auto reads both from that field).
    // It also emits onNowPlayingChanged.
    player.nowPlaying.render()

    player.startRetryLoad()

    // Emits on its own: the incoming track may hold LOADING/BUFFERING and emit no state change.
    player.clearRetryingError()

    player.playbackStateStore.save()
    player.playbackStateStore.resetPeriodicSave()
  }

  override fun onIsPlayingChanged(isPlaying: Boolean) {
    if (player.localEngine.isActive)
      player.retryLoop?.isPlayingChanged(isPlaying && !player.retryMasksPlaying)
  }

  /** A new engine or session player is attached: what this listener knew of the old one is gone. */
  internal fun engineAttached() {
    lastEngineState = player.exoPlayer.playbackState
    localActive = player.localEngine.isActive
  }

  override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
    // A pause during the sleep fade ends the timer early; clearing it restores the pre-fade volume.
    // The timer's own completion stops the fader before pausing, so it never lands here.
    if (!playWhenReady && player.volumeFader.isActive) {
      player.sleepTimer.clear()
    }

    // Here rather than in Player.pause(): a session pause, a noisy-audio pause and a permanent
    // focus loss all drop the intent without it.
    if (!playWhenReady) player.retryFocus.release()
    player.retryLoop?.playIntentChanged(player.shouldPlayWhenReady)
    if (!playWhenReady) player.cancelRetry()
    if (!playWhenReady) player.dropLiveConnection()
    // Deduped: the ENDED path in setPlaybackState may already have emitted it, before queue-ended.
    player.emitPlayWhenReadyChanged(playWhenReady)
    player.refreshPlayingState()
    player.rebuildIfDueLater()

    if (playWhenReady) {
      player.playbackStateStore.startPeriodicSave()
    } else {
      player.playbackStateStore.stopPeriodicSave()
    }
  }

  override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
    player.retryLoop?.playIntentChanged(player.shouldPlayWhenReady)
    player.refreshPlayingState()
    player.rebuildIfDueLater()
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
    // The engine's own state: InterceptingPlayer also sends its view here.
    val engineState = player.sessionPlayer.playbackState
    val left = lastEngineState == MediaPlayer.STATE_IDLE && engineState != MediaPlayer.STATE_IDLE
    lastEngineState = engineState
    val loop = player.retryLoop
    // A prepare: the retry's reconnect, or the listener's play, which after a give-up or a stop is
    // a new Load.
    if (loop != null && player.localEngine.isActive) {
      if (left) {
        if (loop.stopped) player.startRetryLoad() else loop.connectionStarted()
      } else if (
        engineState == MediaPlayer.STATE_IDLE && player.sessionPlayer.playerError == null
      ) {
        // A failure report still on its way belongs to the stopped connection, not the next.
        player.clearRetryingError()
        loop.engineStopped()
      }
    }
    player.rebuildIfDueLater()
  }

  override fun onEvents(media3Player: MediaPlayer, events: MediaPlayer.Events) {
    val local = player.localEngine.isActive
    if (local != localActive) {
      localActive = local
      // The retry loop never acts on a remote output; the phone taking over is a new Load.
      if (local) player.startRetryLoad() else player.retryLoop?.suspend()
    }
    // In event-constant order, not the order the events happened; the transitions rely on it.
    // Each setPlaybackState emits.
    for (i in 0 until events.size()) {
      val event =
        when (events[i]) {
          MediaPlayer.EVENT_PLAYBACK_STATE_CHANGED ->
            // The session player's state, not the forwarding player's: InterceptingPlayer masks
            // IDLE as READY after a terminal error, which would clear our ERROR state here.
            PlaybackEvent.ExoPlaybackStateChanged(
              player.retryView(player.sessionPlayer.playbackState),
              media3Player.mediaItemCount,
              player.shouldPlayWhenReady,
            )
          MediaPlayer.EVENT_MEDIA_ITEM_TRANSITION ->
            PlaybackEvent.MediaItemTransition(
              player.currentTrack != null,
              player.isPlaying,
              loads = player.sessionPlayer.playbackState != MediaPlayer.STATE_IDLE,
              player.shouldPlayWhenReady,
            )
          MediaPlayer.EVENT_PLAY_WHEN_READY_CHANGED,
          MediaPlayer.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED ->
            PlaybackEvent.ShouldPlayWhenReadyChanged(
              player.shouldPlayWhenReady,
              player.retryView(player.sessionPlayer.playbackState),
            )
          MediaPlayer.EVENT_IS_PLAYING_CHANGED ->
            PlaybackEvent.IsPlayingChanged(player.isPlaying && !player.retryMasksPlaying)
          else -> null
        } ?: continue

      for (state in PlaybackStateMachine.transitions(player.playbackState, event)) {
        player.setPlaybackState(state)
      }
    }
    // The retry loop's state moved with these events, after the session's listeners heard them.
    player.forwardingPlayer.refreshViews()
  }

  override fun onPlayerError(error: PlaybackException) {
    if (player.localEngine.isActive && recoverLocally(error)) return
    surface(error)
  }

  /** True when the local engine recovered from [error] itself; false for an error to surface. */
  private fun recoverLocally(error: PlaybackException): Boolean {
    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
      Timber.d("Playback fell behind live window, recovering to live edge")
      player.localEngine.retry(toLiveEdge = true)
      return true
    }

    // media3 1.9+ stuck-player detection wraps a StuckPlayerException (not a PlaybackException) in
    // the cause chain, and the wrapper's errorCode varies by stuck type: match the cause.
    val stuck = stuckCause(error)
    if (stuck != null) {
      Timber.w("Stuck player: type=${stuck.stuckType} timeoutMs=${stuck.timeoutMs}")
      // media3 detects stuck only while playWhenReady, but a paused player never gets recovery
      // work.
      if (!player.playWhenReady) return true
      if (player.retryLoop?.onPlayerError(error, stuck = true) == true) return true
      surfaceStalled(error)
      return true
    }
    return player.retryLoop?.onPlayerError(error, stuck = false) == true
  }

  private fun stuckCause(error: PlaybackException) =
    generateSequence(error.cause) { it.cause }
      .filterIsInstance<StuckPlayerException>()
      .firstOrNull()

  /** The retry loop's give-up: stalled when the last connection ended stuck, as without a loop. */
  internal fun surfaceTerminal(error: PlaybackException) =
    if (stuckCause(error) != null) surfaceStalled(error) else surface(error)

  internal fun surface(error: PlaybackException) {
    val online = player.networkMonitor.getOnline()
    // `code` keeps ExoPlayer's name for telemetry; consumers branch on the classified kind. Offline
    // is the one case ExoPlayer's codes can't tell, so it overrides the code too.
    val code =
      if (!online) {
        "not-connected-to-internet"
      } else {
        error.errorCodeName
          .replace("ERROR_CODE_", "")
          .lowercase(Locale.getDefault())
          .replace("_", "-")
      }
    val playbackError =
      PlaybackError(
        PlaybackErrorClassifier.classify(error, online),
        code,
        error.message ?: "An unknown error occurred",
        PlaybackErrorClassifier.responseCode(error)?.toDouble(),
        retrying = null,
      )
    player.callbacks?.onPlaybackError(playbackError)
    player.playbackError = playbackError
    player.setPlaybackState(PlaybackState.ERROR)
  }

  /** No retry loop takes the stuck engine: it is terminal. */
  private fun surfaceStalled(error: PlaybackException) {
    val playbackError =
      PlaybackError(
        PlaybackErrorKind.STALLED,
        "playback-stalled",
        error.message ?: "Playback stalled",
        null,
        retrying = null,
      )
    player.callbacks?.onPlaybackError(playbackError)
    player.playbackError = playbackError
    player.setPlaybackState(PlaybackState.ERROR)
  }

  override fun onRepeatModeChanged(repeatMode: Int) {
    val mode = RepeatModeFactory.fromMedia3(repeatMode)
    player.callbacks?.onPlaybackRepeatModeChanged(mode)
    player.playbackStateStore.repeatMode = mode
  }

  override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
    player.callbacks?.onPlaybackShuffleModeChanged(shuffleModeEnabled)
    player.playbackStateStore.shuffleEnabled = shuffleModeEnabled
  }

  override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
    player.playbackStateStore.playbackSpeed = playbackParameters.speed
  }

  override fun onTimelineChanged(timeline: Timeline, reason: Int) {
    if (reason == MediaPlayer.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
      player.callbacks?.onPlaybackQueueChanged(player.tracks)
      // Replacing the current item with one of the same URL updates it in place, without a
      // transition, so a changed `live` arrives only here.
      player.updateLiveState(player.currentTrack?.live == true)
    }
  }

  override fun onAudioSessionIdChanged(audioSessionId: Int) {
    Timber.d("Audio session ID changed to: $audioSessionId")
    player.equalizer.onAudioSessionChanged(audioSessionId)
  }
}
