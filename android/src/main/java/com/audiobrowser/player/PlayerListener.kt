package com.audiobrowser.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.extension.NumberExt.Companion.toSeconds
import com.audiobrowser.model.PlaybackMetadata
import com.audiobrowser.util.MetadataAdapter
import com.audiobrowser.util.PlayingStateFactory
import com.audiobrowser.util.RepeatModeFactory
import com.margelo.nitro.audiobrowser.PlaybackActiveTrackChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackPlayWhenReadyChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackState
import timber.log.Timber
import java.util.Locale

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

    // Persist playback state for resumption (use mediaId which is the contextual URL)
    mediaItem?.mediaId?.let { url ->
      player.playbackStateStore.save(url, 0)
    }
  }

  /** Called when the value returned from Player.getPlayWhenReady() changes. */
  override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
    player.callbacks?.onPlaybackPlayWhenReadyChanged(
      PlaybackPlayWhenReadyChangedEvent(playWhenReady)
    )
    val newPlayingState = PlayingStateFactory.derive(playWhenReady, player.playbackState)
    if (newPlayingState != player.playingState) {
      player.playingState = newPlayingState
      player.callbacks?.onPlaybackPlayingState(player.playingState)
    }

    // Persist position on pause for resumption
    if (!playWhenReady) {
      player.savePlaybackStateForResumption()
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
    player.callbacks?.onPlaybackRepeatModeChanged(RepeatModeFactory.fromMedia3(repeatMode))
  }

  override fun onAudioSessionIdChanged(audioSessionId: Int) {
    Timber.d("Audio session ID changed to: $audioSessionId")
    player.reinitializeEqualizer(audioSessionId)
  }
}
