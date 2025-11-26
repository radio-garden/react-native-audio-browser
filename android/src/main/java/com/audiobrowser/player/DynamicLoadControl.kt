package com.audiobrowser.player

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
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

  private val allocator: DefaultAllocator = DefaultAllocator(true, C_DEFAULT_BUFFER_SEGMENT_SIZE)

  // Track last logged values to avoid spam
  @Volatile private var lastRebufferLogTimeMs: Long = 0
  private val logIntervalMs = 1_000L // Log progress every 1 second

  // Track shouldStartPlayback call frequency
  @Volatile private var lastShouldStartCallMs: Long = 0
  @Volatile private var shouldStartCallCount: Int = 0
  @Volatile private var lastShouldStartLogTimeMs: Long = 0

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
    if (config == currentConfig) {
      Timber.d("Buffer config unchanged, skipping update")
      return
    }

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

  // region LoadControl interface implementation

  override fun getAllocator(playerId: PlayerId): Allocator = allocator

  override fun getBackBufferDurationUs(playerId: PlayerId): Long = backBufferUs

  override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = false

  override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
    val bufferedDurationUs = parameters.bufferedDurationUs
    val targetBufferUs =
      if (parameters.playbackSpeed > 1) {
        (minBufferUs * parameters.playbackSpeed).toLong()
      } else {
        minBufferUs
      }

    // Continue loading if we're below the minimum buffer
    if (bufferedDurationUs < targetBufferUs) {
      return true
    }

    // Stop loading if we've reached the maximum buffer
    if (bufferedDurationUs >= maxBufferUs) {
      return false
    }

    // Between min and max: continue loading
    return true
  }

  override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
    val bufferedDurationUs = parameters.bufferedDurationUs
    val bufferedDurationMs = bufferedDurationUs / 1000
    val rebuffering = parameters.rebuffering

    // Track call frequency
    val now = System.currentTimeMillis()
    lastShouldStartCallMs = now
    shouldStartCallCount++

    // Log on first call or every 1 second (not on every call to reduce spam)
    if (
      !rebuffering && (shouldStartCallCount == 1 || now - lastShouldStartLogTimeMs >= logIntervalMs)
    ) {
      lastShouldStartLogTimeMs = now
      Timber.d(
        "shouldStartPlayback: buffer=%dms, call #%d",
        bufferedDurationMs,
        shouldStartCallCount,
      )
    }

    // After rebuffering, use the rebuffer threshold (managed by AutomaticBufferManager)
    if (rebuffering) {
      val targetMs = bufferForPlaybackAfterRebufferUs / 1000
      val adjustedTargetUs =
        if (parameters.playbackSpeed > 1) {
          (bufferForPlaybackAfterRebufferUs * parameters.playbackSpeed).toLong()
        } else {
          bufferForPlaybackAfterRebufferUs
        }
      val shouldStart = bufferedDurationUs >= adjustedTargetUs

      // Log progress at most once per second
      if (now - lastRebufferLogTimeMs >= logIntervalMs) {
        lastRebufferLogTimeMs = now
        Timber.d(
          "Rebuffer progress: %dms / %dms (%.0f%%)",
          bufferedDurationMs,
          targetMs,
          (bufferedDurationMs.toFloat() / targetMs) * 100,
        )
      }
      return shouldStart
    }

    // Reset rebuffer log timer when not rebuffering (so next rebuffer logs immediately)
    lastRebufferLogTimeMs = 0

    // Use fixed threshold for initial playback
    val adjustedTargetUs =
      if (parameters.playbackSpeed > 1) {
        (bufferForPlaybackUs * parameters.playbackSpeed).toLong()
      } else {
        bufferForPlaybackUs
      }
    val shouldStart = bufferedDurationUs >= adjustedTargetUs
    if (shouldStart) {
      logStartupTime(bufferedDurationMs)
    }
    return shouldStart
  }

  private fun logStartupTime(bufferedDurationMs: Long) {
    if (!playbackStarted && prepareStartTimeMs > 0) {
      val startupTimeMs = System.currentTimeMillis() - prepareStartTimeMs
      Timber.d(
        "🎵 PLAYBACK STARTED: %dms startup time, %dms buffer",
        startupTimeMs,
        bufferedDurationMs,
      )
      playbackStarted = true
    }
  }

  override fun onPrepared(playerId: PlayerId) {
    // Reset allocator for new media
    allocator.reset()
    // Reset call tracking for new media
    lastShouldStartCallMs = 0
    shouldStartCallCount = 0
    lastShouldStartLogTimeMs = 0
    // Start timing for startup measurement
    prepareStartTimeMs = System.currentTimeMillis()
    playbackStarted = false
    Timber.d("onPrepared called - starting startup timer")
  }

  override fun onTracksSelected(
    parameters: LoadControl.Parameters,
    trackGroups: TrackGroupArray,
    trackSelections: Array<out ExoTrackSelection?>,
  ) {
    Timber.d("onTracksSelected called - buffer=%dms", parameters.bufferedDurationUs / 1000)
  }

  override fun onStopped(playerId: PlayerId) {
    // No special handling needed
  }

  override fun onReleased(playerId: PlayerId) {
    // Reset allocator when released
    allocator.reset()
  }

  // endregion

  companion object {
    // Default buffer segment size (64KB)
    private const val C_DEFAULT_BUFFER_SEGMENT_SIZE = 64 * 1024
  }
}
