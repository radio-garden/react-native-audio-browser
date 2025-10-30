package com.doublesymmetry.trackplayer.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.doublesymmetry.trackplayer.TrackPlayer
import com.doublesymmetry.trackplayer.event.PlaybackError
import com.doublesymmetry.trackplayer.extension.NumberExt.Companion.toSeconds
import com.doublesymmetry.trackplayer.model.State
import java.util.Locale

class PlayerListener(private val trackPlayer: TrackPlayer) : Player.Listener {
  /** Called when there is metadata associated with the current playback time. */
  override fun onMetadata(metadata: Metadata) {
    trackPlayer.onTimedMetadata(metadata)
  }

  override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
    trackPlayer.onCommonMetadata(mediaMetadata)
  }

  /**
   * A position discontinuity occurs when the playing period changes, the playback position jumps
   * within the period currently being played, or when the playing period has been skipped or
   * removed.
   */
  override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    reason: Int,
  ) {
    trackPlayer.oldPosition = oldPosition.positionMs
    // Position discontinuity events are not currently exposed to callbacks
  }

  /**
   * Called when playback transitions to a media item or starts repeating a media item according to
   * the current repeat mode. Note that this callback is also called when the playlist becomes
   * non-empty or empty as a consequence of a playlist change.
   */
  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    val lastPosition = trackPlayer.oldPosition.toSeconds()
    // Audio item transition events are not currently exposed to callbacks
    // Emit active track changed event with last track info
    trackPlayer.emitActiveTrackChanged(lastPosition)
  }

  /** Called when the value returned from Player.getPlayWhenReady() changes. */
  override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
    val pausedBecauseReachedEnd = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
    trackPlayer.onPlayWhenReadyChanged(playWhenReady, pausedBecauseReachedEnd)
  }

  /**
   * The generic onEvents callback provides access to the Player object and specifies the set of
   * events that occurred together. It's always called after the callbacks that correspond to the
   * individual events.
   */
  override fun onEvents(player: Player, events: Player.Events) {
    // Note that it is necessary to set `playerState` in order, since each mutation fires an
    // event.
    for (i in 0 until events.size()) {
      when (events[i]) {
        Player.EVENT_PLAYBACK_STATE_CHANGED -> {
          val state =
            when (player.playbackState) {
              Player.STATE_BUFFERING -> State.BUFFERING
              Player.STATE_READY -> State.READY
              Player.STATE_IDLE ->
                // Avoid transitioning to idle from error or stopped
                if (
                  trackPlayer.playerState == State.ERROR || trackPlayer.playerState == State.STOPPED
                )
                  null
                else State.NONE
              Player.STATE_ENDED -> if (player.mediaItemCount > 0) State.ENDED else State.NONE
              else -> null // noop
            }
          if (state != null && state != trackPlayer.playerState) {
            // Clear error when recovering from ERROR state to a successful state
            if (trackPlayer.playerState == State.ERROR && state != State.ERROR) {
              trackPlayer.playbackError = null
            }
            trackPlayer.setPlayerState(state)
          }
        }
        Player.EVENT_MEDIA_ITEM_TRANSITION -> {
          trackPlayer.playbackError = null
          if (trackPlayer.currentTrack != null) {
            trackPlayer.setPlayerState(State.LOADING)
            if (trackPlayer.isPlaying) {
              trackPlayer.setPlayerState(State.READY)
              trackPlayer.setPlayerState(State.PLAYING)
            }
          }
        }
        Player.EVENT_PLAY_WHEN_READY_CHANGED -> {
          if (!player.playWhenReady && trackPlayer.playerState != State.STOPPED) {
            trackPlayer.setPlayerState(State.PAUSED)
          }
        }
        Player.EVENT_IS_PLAYING_CHANGED -> {
          if (player.isPlaying) {
            trackPlayer.setPlayerState(State.PLAYING)
          }
        }
      }
    }
  }

  override fun onPlayerError(error: PlaybackException) {
    val playbackError =
      PlaybackError(
        error.errorCodeName
          .replace("ERROR_CODE_", "")
          .lowercase(Locale.getDefault())
          .replace("_", "-"),
        error.message,
      )
    trackPlayer.onPlaybackError(playbackError)
    trackPlayer.playbackError = playbackError
    trackPlayer.setPlayerState(State.ERROR)
  }
}
