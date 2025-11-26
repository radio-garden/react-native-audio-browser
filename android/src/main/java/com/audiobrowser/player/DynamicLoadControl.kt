package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import timber.log.Timber

/**
 * Buffer configuration for DynamicLoadControl.
 *
 * @property minBufferMs Minimum duration of media that the player will attempt to buffer.
 * @property maxBufferMs Maximum duration of media that the player will attempt to buffer.
 * @property bufferForPlaybackMs Duration of media that must be buffered for playback to start.
 * @property bufferForPlaybackAfterRebufferMs Duration of media that must be buffered for playback
 *   to resume after a rebuffer.
 * @property backBufferMs Duration of media to keep in the buffer behind the current position.
 */
data class BufferConfig(
  val minBufferMs: Int = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
  val maxBufferMs: Int = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
  val bufferForPlaybackMs: Int = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
  val bufferForPlaybackAfterRebufferMs: Int =
    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
  val backBufferMs: Int = 0,
)

/**
 * A LoadControl implementation that allows runtime buffer configuration changes.
 *
 * Unlike wrapping DefaultLoadControl, this implementation has its own mutable buffer thresholds
 * that can be changed at any time during playback. This allows adaptive buffer management (via
 * AutomaticBufferManager) to take effect immediately.
 *
 * Thread safety: LoadControl methods are called from ExoPlayer's internal playback thread, while
 * [updateBufferConfig] is typically called from the main thread. The @Volatile annotation ensures
 * visibility across threads.
 */
class DynamicLoadControl(initialConfig: BufferConfig = BufferConfig()) : LoadControl {

  private val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

  // Track startup timing
  @Volatile private var prepareStartTimeMs: Long = 0
  @Volatile private var playbackStarted: Boolean = false

  // Mutable buffer thresholds - can be updated at runtime
  @Volatile private var minBufferUs: Long = initialConfig.minBufferMs * 1000L

  @Volatile private var maxBufferUs: Long = initialConfig.maxBufferMs * 1000L

  @Volatile private var bufferForPlaybackUs: Long = initialConfig.bufferForPlaybackMs * 1000L

  @Volatile
  private var bufferForPlaybackAfterRebufferUs: Long =
    initialConfig.bufferForPlaybackAfterRebufferMs * 1000L

  @Volatile private var backBufferUs: Long = initialConfig.backBufferMs * 1000L

  @Volatile private var currentConfig: BufferConfig = initialConfig

  /**
   * Updates the buffer configuration at runtime.
   *
   * The new configuration takes effect immediately for future buffering decisions. Already-buffered
   * data is not affected.
   *
   * @param config The new buffer configuration to apply.
   */
  fun updateBufferConfig(config: BufferConfig) {
    if (config == currentConfig) return

    Timber.d(
      "Updating buffer config: playBuffer=%dms -> %dms, rebufferBuffer=%dms -> %dms",
      currentConfig.bufferForPlaybackMs,
      config.bufferForPlaybackMs,
      currentConfig.bufferForPlaybackAfterRebufferMs,
      config.bufferForPlaybackAfterRebufferMs,
    )

    currentConfig = config
    minBufferUs = config.minBufferMs * 1000L
    maxBufferUs = config.maxBufferMs * 1000L
    bufferForPlaybackUs = config.bufferForPlaybackMs * 1000L
    bufferForPlaybackAfterRebufferUs = config.bufferForPlaybackAfterRebufferMs * 1000L
    backBufferUs = config.backBufferMs * 1000L
  }

  /** Gets the current buffer configuration. */
  fun getBufferConfig(): BufferConfig = currentConfig

  /** Resets the buffer configuration to defaults. */
  fun resetToDefaults() {
    updateBufferConfig(BufferConfig())
  }

  override fun getAllocator(playerId: PlayerId): Allocator = allocator

  override fun getBackBufferDurationUs(playerId: PlayerId): Long = backBufferUs

  override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = false

  override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
    return parameters.bufferedDurationUs < maxBufferUs
  }

  override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
    val bufferedDurationUs = parameters.bufferedDurationUs
    val targetUs = adjustForPlaybackSpeed(
      if (parameters.rebuffering) bufferForPlaybackAfterRebufferUs else bufferForPlaybackUs,
      parameters.playbackSpeed,
    )
    val shouldStart = bufferedDurationUs >= targetUs
    if (shouldStart && !parameters.rebuffering) {
      logStartupTime(bufferedDurationUs / 1000)
    }
    return shouldStart
  }

  private fun adjustForPlaybackSpeed(bufferUs: Long, playbackSpeed: Float): Long {
    return if (playbackSpeed > 1) (bufferUs * playbackSpeed).toLong() else bufferUs
  }

  private fun logStartupTime(bufferedDurationMs: Long) {
    if (!playbackStarted && prepareStartTimeMs > 0) {
      val startupTimeMs = System.currentTimeMillis() - prepareStartTimeMs
      Timber.d(
        "Playback started: %dms startup time, %dms buffer",
        startupTimeMs,
        bufferedDurationMs,
      )
      playbackStarted = true
    }
  }

  override fun onPrepared(playerId: PlayerId) {
    allocator.reset()
    prepareStartTimeMs = System.currentTimeMillis()
    playbackStarted = false
  }

  override fun onReleased(playerId: PlayerId) {
    // Reset allocator when released
    allocator.reset()
  }

}
