package com.audiobrowser.player

import androidx.media3.common.Player as MediaPlayer

/**
 * What the listener and the session are shown of the local engine while [RetryLoop] holds it: its
 * idle engine reads buffering, and so does media3's READY blip. Once a load fails, media3 stops
 * loading, and with audio still in the sink it reads ready for a moment before the error surfaces,
 * with nothing buffered. A READY with buffered audio is real.
 */
internal object RetryView {
  /** Buffered audio below which a READY over a failed connection is the blip. */
  const val BLIP_BUFFERED_MS = 100L

  @MediaPlayer.State
  fun engineState(
    @MediaPlayer.State engineState: Int,
    reconnecting: Boolean,
    failurePending: Boolean,
    bufferedMs: Long,
  ): Int =
    when {
      engineState == MediaPlayer.STATE_IDLE && reconnecting -> MediaPlayer.STATE_BUFFERING
      engineState == MediaPlayer.STATE_READY && isBlip(failurePending, bufferedMs) ->
        MediaPlayer.STATE_BUFFERING
      else -> engineState
    }

  fun isBlip(failurePending: Boolean, bufferedMs: Long): Boolean =
    failurePending && bufferedMs < BLIP_BUFFERED_MS
}
