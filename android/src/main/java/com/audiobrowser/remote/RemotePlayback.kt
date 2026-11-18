package com.audiobrowser.remote

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer

/**
 * A remote output (a Cast receiver or a Sonos room) as [RemoteSwitchingPlayer] drives it, kept
 * apart from its SDK or protocol so the library builds without them.
 */
internal interface RemotePlayback {
  /**
   * The receiver of the current session; read it only while [isSessionAvailable]. It plays one item
   * at a time: setting an item loads it there from the given position, and plays it if
   * `playWhenReady` is set, without [MediaPlayer.prepare]. A new session can bring a new player.
   */
  val player: MediaPlayer

  val isSessionAvailable: Boolean

  /** Calls [listener] on the main thread; null stops the calls. */
  fun setListener(listener: Listener?)

  /** Ends the session on this side; the receiver may stop or play on. */
  fun release()

  interface Listener {
    /** [isSessionAvailable] or [player] changed. */
    fun onSessionChanged()

    /**
     * The receiver reports its item playing. [player]'s play state turns to playing as soon as play
     * is asked, before the receiver does.
     */
    fun onPlaying()

    /** The receiver played its item to the end: the only end the switch acts on. */
    fun onEnded()

    /** The receiver failed to play its item: the only failure the switch acts on. */
    fun onFailed(error: PlaybackException)
  }
}
