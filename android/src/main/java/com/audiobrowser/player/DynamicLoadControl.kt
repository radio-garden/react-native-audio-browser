package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import timber.log.Timber

data class BufferConfig(
  val minBufferMs: Int = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
  val maxBufferMs: Int = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
  val bufferForPlaybackMs: Int = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
  val bufferForPlaybackAfterRebufferMs: Int =
    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
  val backBufferMs: Int = 0,
)

/**
 * A LoadControl whose buffer thresholds change during playback through [updateBufferConfig], which
 * DefaultLoadControl fixes at construction.
 *
 * ExoPlayer calls the LoadControl methods on its playback thread; the other methods may run on any
 * thread, so shared state is @Volatile.
 */
class DynamicLoadControl(initialConfig: BufferConfig = BufferConfig()) : LoadControl {

  private val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

  @Volatile private var prepareStartTimeMs: Long = 0
  @Volatile private var playbackStarted: Boolean = false

  @Volatile private var rebuffering: Boolean = false

  /**
   * Whether ExoPlayer reports a stall after playback had started, as opposed to an initial connect,
   * a seek or a track change.
   */
  val isRebuffering: Boolean
    get() = rebuffering

  @Volatile private var minBufferUs: Long = initialConfig.minBufferMs * 1000L

  @Volatile private var maxBufferUs: Long = initialConfig.maxBufferMs * 1000L

  @Volatile private var bufferForPlaybackUs: Long = initialConfig.bufferForPlaybackMs * 1000L

  @Volatile
  private var bufferForPlaybackAfterRebufferUs: Long =
    initialConfig.bufferForPlaybackAfterRebufferMs * 1000L

  @Volatile private var backBufferUs: Long = initialConfig.backBufferMs * 1000L

  @Volatile private var currentConfig: BufferConfig = initialConfig

  /** Applies from the next buffering decision; media already buffered stays. */
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

  fun getBufferConfig(): BufferConfig = currentConfig

  /** Resets to media3's DefaultLoadControl defaults, not to the config this was built with. */
  fun resetToDefaults() {
    updateBufferConfig(BufferConfig())
  }

  // Each instance serves a single ExoPlayer, so the one allocator is returned for any playerId.
  override fun getAllocator(playerId: PlayerId): Allocator = allocator

  override fun getBackBufferDurationUs(): Long = backBufferUs

  override fun retainBackBufferFromKeyframe(): Boolean = false

  override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
    // ExoPlayer polls this in every playback state, unlike shouldStartPlayback, so this is where
    // the flag clears once a rebuffer recovers.
    rebuffering = parameters.rebuffering
    return parameters.bufferedDurationUs < maxBufferUs
  }

  override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
    rebuffering = parameters.rebuffering
    val bufferedDurationUs = parameters.bufferedDurationUs
    val targetUs =
      adjustForPlaybackSpeed(
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

  override fun onPrepared() {
    allocator.reset()
    prepareStartTimeMs = System.currentTimeMillis()
    playbackStarted = false
    rebuffering = false
  }

  override fun onTracksSelected(
    parameters: LoadControl.Parameters,
    trackGroups: TrackGroupArray,
    trackSelections: Array<out ExoTrackSelection?>,
  ) {
    // No byte target from the selected tracks: loading is bounded by maxBufferUs alone.
  }

  override fun onStopped() {}

  override fun onReleased() {
    allocator.reset()
  }
}
