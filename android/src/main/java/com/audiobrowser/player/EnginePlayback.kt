package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder

/**
 * An engine's queue, position and playback settings, which [Player.setup] carries from the engine
 * it releases to the one it builds so that new setup options don't interrupt playback.
 */
internal data class EnginePlayback(
  val mediaItems: List<MediaItem>,
  val index: Int,
  /**
   * [C.TIME_UNSET] for a live item, so it resumes at the live edge, for 0, which is also what a
   * live item reports before it has loaded, and after the end, so a play restarts the track.
   */
  val positionMs: Long,
  val prepared: Boolean,
  val playWhenReady: Boolean,
  val repeatMode: Int,
  val shuffleModeEnabled: Boolean,
  val shuffleOrder: ShuffleOrder,
  val playbackParameters: PlaybackParameters,
  val volume: Float,
  val skipSilenceEnabled: Boolean,
) {
  fun restoreTo(exoPlayer: ExoPlayer) {
    exoPlayer.repeatMode = repeatMode
    exoPlayer.shuffleModeEnabled = shuffleModeEnabled
    exoPlayer.playbackParameters = playbackParameters
    exoPlayer.volume = volume
    exoPlayer.skipSilenceEnabled = skipSilenceEnabled
    if (mediaItems.isNotEmpty()) {
      exoPlayer.setMediaItems(mediaItems, index, positionMs)
      exoPlayer.setShuffleOrder(shuffleOrder)
      if (prepared) exoPlayer.prepare()
    }
    exoPlayer.playWhenReady = playWhenReady
  }

  companion object {
    fun of(exoPlayer: ExoPlayer) =
      EnginePlayback(
        mediaItems = (0 until exoPlayer.mediaItemCount).map { exoPlayer.getMediaItemAt(it) },
        index = exoPlayer.currentMediaItemIndex,
        positionMs =
          exoPlayer.currentPosition.takeUnless {
            exoPlayer.isCurrentMediaItemLive ||
              it == 0L ||
              exoPlayer.playbackState == MediaPlayer.STATE_ENDED
          } ?: C.TIME_UNSET,
        prepared =
          exoPlayer.playbackState !in setOf(MediaPlayer.STATE_IDLE, MediaPlayer.STATE_ENDED),
        playWhenReady = exoPlayer.playWhenReady,
        repeatMode = exoPlayer.repeatMode,
        shuffleModeEnabled = exoPlayer.shuffleModeEnabled,
        shuffleOrder = exoPlayer.shuffleOrder,
        playbackParameters = exoPlayer.playbackParameters,
        volume = exoPlayer.volume,
        skipSilenceEnabled = exoPlayer.skipSilenceEnabled,
      )
  }
}
