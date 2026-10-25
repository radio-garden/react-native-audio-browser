package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder

/**
 * What an engine is playing, and how: what [Player.setup] carries from the engine it releases to
 * the one it builds, so new setup options don't stop playback.
 */
internal data class EnginePlayback(
  val mediaItems: List<MediaItem>,
  val index: Int,
  /**
   * [C.TIME_UNSET] for a live item, which resumes at the live edge, and for 0, which is also how a
   * live item reads before it has loaded.
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
          exoPlayer.currentPosition.takeUnless { exoPlayer.isCurrentMediaItemLive || it == 0L }
            ?: C.TIME_UNSET,
        prepared = exoPlayer.playbackState != MediaPlayer.STATE_IDLE,
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
