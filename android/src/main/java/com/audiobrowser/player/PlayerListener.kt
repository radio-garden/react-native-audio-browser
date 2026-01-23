package com.audiobrowser.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.Timeline
import com.audiobrowser.extension.NumberExt.Companion.toSeconds
import com.audiobrowser.model.PlaybackMetadata
import com.audiobrowser.util.MetadataAdapter
import com.audiobrowser.util.PlayingStateFactory
import com.audiobrowser.util.RepeatModeFactory
import com.margelo.nitro.audiobrowser.PlaybackActiveTrackChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackPlayWhenReadyChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackState
import java.util.Locale
import timber.log.Timber

class PlayerListener(private val player: Player) : MediaPlayer.Listener {
  /** Called when there is metadata associated with the current playback time. */
  override fun onMetadata(metadata: Metadata) {
    // Parse playback metadata from different formats
    val playbackMetadata =
      PlaybackMetadata.Companion.fromId3Metadata(metadata)
        ?: PlaybackMetadata.Companion.fromIcy(metadata)
        ?: PlaybackMetadata.Companion.fromVorbisComment(metadata)
        ?: PlaybackMetadata.Companion.fromQuickTime(metadata)

    playbackMetadata?.let { player.callbacks?.onPlaybackMetadata(it) }
  }

  override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
    player.callbacks?.onMetadataCommonReceived(
      MetadataAdapter.Companion.audioMetadataFromMediaMetadata(mediaMetadata)
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
    player.clearNowPlayingOverride()

    // Reset retry timer so new track gets fresh 2-minute window
    player.resetRetryTimer()

    // Notify JS of the now playing metadata for the new track
    player.getNowPlaying()?.let { player.callbacks?.onNowPlayingChanged(it) }

    player.playbackStateStore.save()
    player.playbackStateStore.resetPeriodicSave()
  }

  /** Called when the value returned from Player.getPlayWhenReady() changes. */
  override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
    // Update thread-safe cache for access from non-main threads (e.g., retry policy)
    player.playWhenReadyCache = playWhenReady
    player.callbacks?.onPlaybackPlayWhenReadyChanged(
      PlaybackPlayWhenReadyChangedEvent(playWhenReady)
    )
    val newPlayingState = PlayingStateFactory.derive(playWhenReady, player.playbackState)
    if (newPlayingState != player.playingState) {
      player.playingState = newPlayingState
      player.callbacks?.onPlaybackPlayingState(player.playingState)
    }

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
          val state =
            when (media3Player.playbackState) {
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

    val playbackError =
      PlaybackError(
        error.errorCodeName
          .replace("ERROR_CODE_", "")
          .lowercase(Locale.getDefault())
          .replace("_", "-"),
        error.message ?: "An unknown error occurred",
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
