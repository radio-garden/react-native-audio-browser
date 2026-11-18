package com.audiobrowser.player

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder

/**
 * The phone's ExoPlayer, for the work only it does: retries, reconnects and its shuffle order.
 * While a remote output plays, each call does nothing.
 */
internal class LocalEngine(
  private val engine: () -> ExoPlayer,
  private val isRemote: () -> Boolean,
) {
  private val playing: ExoPlayer?
    get() = engine().takeIf { !isRemote() }

  /** Whether the phone's ExoPlayer is what plays, rather than a remote output. */
  val isActive: Boolean
    get() = playing != null

  /** Prepares again what failed or stalled, from the live edge when [toLiveEdge]. */
  fun retry(toLiveEdge: Boolean = false) {
    val engine = playing ?: return
    if (toLiveEdge) engine.seekToDefaultPosition()
    engine.prepare()
  }

  /**
   * Reconnects at the live edge: a stream with a live window seeks there; anything else connects
   * again, which for a stream that can't seek is the edge. False while remote.
   */
  fun reconnectAtLiveEdge(): Boolean {
    val engine = playing ?: return false
    if (engine.isCurrentMediaItemLive) {
      engine.seekToDefaultPosition()
      return true
    }
    // prepare() only re-prepares from STATE_IDLE, hence ExoPlayer's own stop(), not
    // [Player.stop], which drops the play intent this reconnect keeps.
    engine.stop()
    engine.seekToDefaultPosition()
    engine.prepare()
    return true
  }

  fun setShuffleOrder(order: ShuffleOrder) {
    playing?.setShuffleOrder(order)
  }
}
