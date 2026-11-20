package androidx.media3.session

/** Media3's own default, which [MediaSession] has no getter for. */
private const val PERIODIC_POSITION_UPDATE_DELAY_MS = 3_000L

/**
 * Pauses or resumes the session's periodic position updates through media3's package-private
 * `MediaSession.setSessionPositionUpdateDelayMs`, as of media3 1.11.1; a resume does not override
 * the builder's `setPeriodicPositionUpdateEnabled(false)`. Application thread only.
 */
internal fun MediaSession.setPeriodicPositionUpdatesPaused(paused: Boolean) =
  setSessionPositionUpdateDelayMs(if (paused) 0 else PERIODIC_POSITION_UPDATE_DELAY_MS)
