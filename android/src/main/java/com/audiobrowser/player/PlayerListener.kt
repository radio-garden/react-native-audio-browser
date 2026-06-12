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
import com.margelo.nitro.audiobrowser.PlaybackPlayWhenReadyChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackState
import java.util.Locale
import timber.log.Timber

// Continuous playback required to refill the stuck-recovery budget. Long enough that a stream which
// keeps re-stalling can't "prove liveness" between stalls, short enough to forgive a single hiccup.
private const val HEALTHY_PLAYBACK_MS = 20_000L

class PlayerListener(private val player: Player) : MediaPlayer.Listener {
  // Caps auto-recovery from media3 stuck-player detection before surfacing a terminal error.
  private val stuckRecoveryPolicy = StuckRecoveryPolicy()

  // Proof-of-life timer: the recovery budget refills only after the player sustains continuous
  // playback for HEALTHY_PLAYBACK_MS, proving the stream actually delivered audio (not a brief blip
  // before re-stalling). Mirrors AutomaticBufferManager's Handler usage. Runs on the main thread,
  // where Player.Listener callbacks are delivered.
  private val healthyPlaybackHandler = Handler(Looper.getMainLooper())
  private val healthyPlaybackRunnable = Runnable { stuckRecoveryPolicy.reset() }

  /** Called when there is metadata associated with the current playback time. */
  override fun onMetadata(metadata: Metadata) {
    // Extract and emit chapter metadata if present
    val chapters = MetadataAdapter.extractChapters(metadata)
    if (chapters.isNotEmpty()) {
      player.callbacks?.onChapterMetadata(chapters)
    }

    // Extract and emit timed metadata (ICY, ID3, etc.)
    PlaybackMetadata.from(metadata)?.let {
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

  /**
   * A position discontinuity occurs when the playing period changes, the playback position jumps
   * within the period currently being played, or when the playing period has been skipped or
   * removed.
   */
  override fun onPositionDiscontinuity(
    oldPosition: MediaPlayer.PositionInfo,
    newPosition: MediaPlayer.PositionInfo,
    reason: Int,
  ) {
    player.oldPosition = oldPosition.positionMs
    // Position discontinuity events are not currently exposed to callbacks
  }

  /**
   * Called when playback transitions to a media item or starts repeating a media item according to
   * the current repeat mode. Note that this callback is also called when the playlist becomes
   * non-empty or empty as a consequence of a playlist change.
   */
  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    val lastPosition = player.oldPosition.toSeconds()
    // Audio item transition events are not currently exposed to callbacks
    // Emit active track changed event with last track info
    val event =
      PlaybackActiveTrackChangedEvent(
        lastIndex = player.lastIndex?.toDouble(),
        lastTrack = player.lastTrack,
        lastPosition = lastPosition,
        index = player.currentIndex?.toDouble(),
        track = player.currentTrack,
      )
    player.callbacks?.onPlaybackActiveTrackChanged(event)

    // Check if sleep timer should trigger on track end
    if (reason == MediaPlayer.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
      player.checkSleepTimerOnTrackEnd()
    }

    // Update last track info for next transition
    player.lastTrack = player.currentTrack
    player.lastIndex = player.currentIndex

    // Update favorite button state for new track
    player.updateFavoriteButtonState(player.currentTrack?.favorited)

    // Clear now playing override when track changes (new track = clean slate)
    player.nowPlaying.clearOverride()

    // Re-stamp the now-playing metadata for the new track. The browse-list
    // MediaItem carries `artist = subtitle` (the per-context list line), but the
    // now-playing screen / lock screen / Bluetooth should show the canonical
    // `artist` (location). Android Auto derives both from the same MediaItem's
    // `artist`, so we overwrite the playing item's metadata here (the same
    // mechanism ICY song updates already use) to diverge it from the list items.
    // This also fires onNowPlayingChanged, so we don't emit it separately below.
    player.nowPlaying.render()

    // Reset retry timer so new track gets fresh 2-minute window
    player.resetRetryTimer()

    // A new station starts with a clean stuck-recovery budget, mirroring the retry timer reset.
    healthyPlaybackHandler.removeCallbacks(healthyPlaybackRunnable)
    stuckRecoveryPolicy.reset()

    player.playbackStateStore.save()
    player.playbackStateStore.resetPeriodicSave()
  }

  /**
   * Schedules a stuck-recovery budget refill once the player has been continuously playing for
   * [HEALTHY_PLAYBACK_MS], and cancels it the moment playback stops (pause, rebuffer, stall,
   * error). Sustained real playback is our proof the stream is alive and the budget can be trusted
   * again.
   */
  override fun onIsPlayingChanged(isPlaying: Boolean) {
    healthyPlaybackHandler.removeCallbacks(healthyPlaybackRunnable)
    if (isPlaying) {
      healthyPlaybackHandler.postDelayed(healthyPlaybackRunnable, HEALTHY_PLAYBACK_MS)
    }
  }

  /** Called when the value returned from Player.getPlayWhenReady() changes. */
  override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
    // An explicit pause during the sleep fade is the timer's goal arriving early: clear the
    // timer (restores the pre-fade volume). The timer's own completion cancels the fader
    // before pausing, so it never re-enters here.
    if (!playWhenReady && player.volumeFader.isActive) {
      player.clearSleepTimer()
    }

    // Update thread-safe cache for access from non-main threads (e.g., retry policy).
    // Deliberately the second writer: Player.playWhenReady's setter writes eagerly so
    // non-main readers see the new intent before ExoPlayer round-trips; this event is
    // the authoritative sync for changes ExoPlayer makes on its own (e.g. audio focus).
    player.playWhenReadyCache = playWhenReady
    player.callbacks?.onPlaybackPlayWhenReadyChanged(
      PlaybackPlayWhenReadyChangedEvent(playWhenReady)
    )
    player.refreshPlayingState()

    if (playWhenReady) {
      player.playbackStateStore.startPeriodicSave()
    } else {
      player.playbackStateStore.stopPeriodicSave()
    }
  }

  /**
   * The generic onEvents callback provides access to the Player object and specifies the set of
   * events that occurred together. It's always called after the callbacks that correspond to the
   * individual events.
   */
  override fun onEvents(media3Player: MediaPlayer, events: MediaPlayer.Events) {
    // Note that it is necessary to set `playerState` in order, since each mutation fires an
    // event.
    for (i in 0 until events.size()) {
      when (events[i]) {
        MediaPlayer.EVENT_PLAYBACK_STATE_CHANGED -> {
          // Read the real ExoPlayer state, not the forwarding player's — the InterceptingPlayer
          // masks STATE_IDLE→READY on a terminal error to keep the session alive, and that mask
          // must not feed back into our own state machine (it would clear the ERROR
          // state/subtitle).
          val state =
            when (player.exoPlayer.playbackState) {
              MediaPlayer.STATE_BUFFERING -> PlaybackState.BUFFERING
              MediaPlayer.STATE_READY -> PlaybackState.READY
              MediaPlayer.STATE_IDLE ->
                // Avoid transitioning to idle from error or stopped
                if (
                  player.playbackState == PlaybackState.ERROR ||
                    player.playbackState == PlaybackState.STOPPED
                )
                  null
                else PlaybackState.NONE
              MediaPlayer.STATE_ENDED ->
                if (media3Player.mediaItemCount > 0) PlaybackState.ENDED else PlaybackState.NONE
              else -> null // noop
            }
          if (state != null && state != player.playbackState) {
            // Clear error when recovering from ERROR state to a successful state
            if (player.playbackState == PlaybackState.ERROR) {
              player.playbackError = null
            }
            player.setPlaybackState(state)
          }
        }
        MediaPlayer.EVENT_MEDIA_ITEM_TRANSITION -> {
          player.playbackError = null
          if (player.currentTrack != null) {
            player.setPlaybackState(PlaybackState.LOADING)
            if (player.isPlaying) {
              player.setPlaybackState(PlaybackState.READY)
              player.setPlaybackState(PlaybackState.PLAYING)
            }
          }
        }
        MediaPlayer.EVENT_PLAY_WHEN_READY_CHANGED -> {
          if (!player.playWhenReady && player.playbackState != PlaybackState.STOPPED) {
            player.setPlaybackState(PlaybackState.PAUSED)
          }
        }
        MediaPlayer.EVENT_IS_PLAYING_CHANGED -> {
          if (player.isPlaying) {
            player.setPlaybackState(PlaybackState.PLAYING)
          }
        }
      }
    }
  }

  override fun onPlayerError(error: PlaybackException) {
    // Handle live stream recovery when playback position falls behind the live window
    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
      Timber.d("Playback fell behind live window, recovering to live edge")
      player.exoPlayer.seekToDefaultPosition()
      player.exoPlayer.prepare()
      return
    }

    // media3 1.9.0+ stuck-player detection arrives here wrapped in a PlaybackException: the actual
    // StuckPlayerException (an IllegalStateException, not a PlaybackException) sits in the cause
    // chain, and the wrapper's errorCode varies by stuck type — so we detect by cause, not code.
    // For a live-radio app a transient stall should reconnect, not stop playback.
    val stuck =
      generateSequence(error.cause) { it.cause }
        .filterIsInstance<StuckPlayerException>()
        .firstOrNull()
    if (stuck != null) {
      handleStuckPlayer(stuck)
      return
    }

    // When the device is offline at the moment of failure, normalize to a cross-platform code
    // (matching iOS) so consumers can reliably tell "no internet" from a broken/unreachable stream.
    // ExoPlayer's own error codes don't distinguish the two; the connectivity monitor does.
    val code =
      if (!player.networkMonitor.getOnline()) {
        "not-connected-to-internet"
      } else {
        error.errorCodeName
          .replace("ERROR_CODE_", "")
          .lowercase(Locale.getDefault())
          .replace("_", "-")
      }
    val playbackError = PlaybackError(code, error.message ?: "An unknown error occurred")
    player.callbacks?.onPlaybackError(playbackError)
    player.playbackError = playbackError
    player.setPlaybackState(PlaybackState.ERROR)
  }

  /**
   * Recovers from a media3 stuck-player detection or, once the recovery budget is exhausted,
   * surfaces a distinct terminal error. Live items rejoin the live edge (a stale buffered live
   * window is the common stuck cause); on-demand items re-prepare in place so the saved position is
   * preserved.
   */
  private fun handleStuckPlayer(error: StuckPlayerException) {
    // Defensive: media3 only fires stuck detection while playWhenReady is true, but never do
    // recovery work for a deliberately-paused player (matches RetryLoadErrorHandlingPolicy).
    if (!player.playWhenReady) return

    when (stuckRecoveryPolicy.onStuck()) {
      StuckRecoveryPolicy.Decision.RECOVER -> {
        Timber.w(
          "Stuck player recovery: type=${error.stuckType} timeoutMs=${error.timeoutMs} " +
            "live=${player.isCurrentItemLive}"
        )
        if (player.isCurrentItemLive) {
          player.exoPlayer.seekToDefaultPosition()
        }
        player.exoPlayer.prepare()
      }
      StuckRecoveryPolicy.Decision.GIVE_UP -> {
        Timber.w("Stuck player recovery exhausted (type=${error.stuckType}), surfacing error")
        val playbackError = PlaybackError("playback-stalled", error.message ?: "Playback stalled")
        player.callbacks?.onPlaybackError(playbackError)
        player.playbackError = playbackError
        player.setPlaybackState(PlaybackState.ERROR)
      }
    }
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

  /**
   * Called when the timeline changes (playlist add/remove/reorder). We use this to emit queue
   * changed events to JS.
   */
  override fun onTimelineChanged(timeline: Timeline, reason: Int) {
    // Only emit for playlist changes, not initial load or other reasons
    if (reason == MediaPlayer.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
      player.callbacks?.onPlaybackQueueChanged(player.tracks)
    }
  }

  override fun onAudioSessionIdChanged(audioSessionId: Int) {
    Timber.d("Audio session ID changed to: $audioSessionId")
    player.reinitializeEqualizer(audioSessionId)
  }
}
