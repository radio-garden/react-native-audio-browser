package com.audiobrowser.model

enum class State {
  /** The current [com.audiobrowser.model.Track] is being loaded for playback. */
  LOADING,

  /**
   * The current [com.audiobrowser.model.Track] is loaded, and the player is ready to
   * start playing.
   */
  READY,

  /** The current [com.audiobrowser.model.Track] is currently buffering. */
  BUFFERING,

  /** The player is paused. */
  PAUSED,

  /** The player is stopped. */
  STOPPED,

  /** The player is playing. */
  PLAYING,

  /** No [com.audiobrowser.model.Track] is loaded and the player is doing nothing. */
  NONE,

  /** Playback stopped due to the end of the queue being reached. */
  ENDED,

  /** The player stopped playing due to an error. */
  ERROR,
}

val State.bridge: String
  get() {
    return when (this) {
      State.LOADING -> "loading"
      State.READY -> "ready"
      State.BUFFERING -> "buffering"
      State.PAUSED -> "paused"
      State.PLAYING -> "playing"
      State.NONE -> "none"
      State.ENDED -> "ended"
      State.ERROR -> "error"
      State.STOPPED -> "stopped"
    }
  }
