package com.audiobrowser.player

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder

/**
 * The phone's engine for the work only it does: retries, reconnects and its shuffle order. While a
 * remote output plays, none of it reaches the remote: each call does nothing.
 */
internal class LocalEngine(
  private val engine: () -> ExoPlayer,
  private val isRemote: () -> Boolean,
) {
  private val playing: ExoPlayer?
    get() = engine().takeIf { !isRemote() }

  /** Whether the engine is what plays. */
  val isActive: Boolean
    get() = playing != null

  /** Loads again what failed or stalled, from the live edge when [toLiveEdge]. */
  fun retry(toLiveEdge: Boolean = false) {
    val engine = playing ?: return
    if (toLiveEdge) engine.seekToDefaultPosition()
    engine.prepare()
  }

  /**
   * Reconnects a live stream at its edge: a stream with a live window seeks there; anything else
   * connects again, which for a stream that can't seek is the edge. False while remote.
   */
  fun reconnectAtLiveEdge(): Boolean {
    val engine = playing ?: return false
    if (engine.isCurrentMediaItemLive) {
      engine.seekToDefaultPosition()
      return true
    }
    // ExoPlayer.prepare() only re-prepares from STATE_IDLE, hence the raw stop() — not
    // Player.stop(), which would drop the play intent this reconnect must preserve.
    engine.stop()
    engine.seekToDefaultPosition()
    engine.prepare()
    return true
  }

  fun setShuffleOrder(order: ShuffleOrder) {
    playing?.setShuffleOrder(order)
  }
}
