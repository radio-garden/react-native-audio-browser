package com.audiobrowser.remote

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer

/**
 * A remote output's side of [RemoteSwitchingPlayer]: a Cast receiver or a Sonos room, each kept
 * apart from its SDK or protocol so the rest of the library compiles without them.
 */
internal interface RemotePlayback {
  /**
   * Plays one item at a time on the receiver, read while [isSessionAvailable]. Setting its item
   * loads it there, from the start position given, and plays as its play intent says, without
   * [MediaPlayer.prepare]. A session can bring a new player: the switch moves playback to it.
   */
  val player: MediaPlayer

  val isSessionAvailable: Boolean

  /** Calls [listener] on the main thread; null stops the calls. */
  fun setListener(listener: Listener?)

  /** Ends the session on this side; the receiver stops or plays on as it was told. */
  fun release()

  interface Listener {
    /** [isSessionAvailable] or [player] changed. */
    fun onSessionChanged()

    /**
     * The receiver reports its item playing, as it plays it: [player]'s own play state follows a
     * play as soon as it is asked.
     */
    fun onPlaying()

    /** The receiver played its item to the end: the only end the switch acts on. */
    fun onEnded()

    /** The receiver failed to play its item: the only failure the switch acts on. */
    fun onFailed(error: PlaybackException)
  }
}
