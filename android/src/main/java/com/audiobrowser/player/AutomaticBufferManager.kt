package com.audiobrowser.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import timber.log.Timber

/**
 * Raises [DynamicLoadControl]'s `bufferForPlaybackAfterRebufferMs` after each rebuffer, to what the
 * measured drain rate says would last [targetPlaybackDurationMs]. The threshold only rises, up to
 * [maxRebufferBufferMs], until a playlist change or a seek to another item resets it to
 * [defaultConfig].
 */
class AutomaticBufferManager(
  private val loadControl: DynamicLoadControl,
  private val defaultConfig: BufferConfig = BufferConfig(),
) : AnalyticsListener, Player.Listener {

  private var exoPlayer: ExoPlayer? = null

  private var wasReady = false
  private var rebufferCount = 0

  private var playbackStartTimeMs: Long = 0
  private var bufferAtPlaybackStartMs: Long = 0
  private var bufferingStartTimeMs: Long = 0

  private val maxRebufferBufferMs = 8_000
  // How long playback should run without stalling once the raised threshold is met.
  private val targetPlaybackDurationMs = 60_000L

  fun attach(player: ExoPlayer) {
    exoPlayer = player
    player.addAnalyticsListener(this)
    player.addListener(this)
    Timber.d("AutomaticBufferManager attached to player")
  }

  /** Call before releasing the player. */
  fun detach() {
    stopBufferMonitoring()
    bufferMonitorHandler = null
    exoPlayer?.removeAnalyticsListener(this)
    exoPlayer?.removeListener(this)
    exoPlayer = null
    Timber.d("AutomaticBufferManager detached from player")
  }

  /**
   * Restores [defaultConfig] — the setup's buffer tuning, not Media3's defaults, which would wipe a
   * fast-start configuration — and clears the rebuffer tracking.
   */
  fun reset() {
    loadControl.updateBufferConfig(defaultConfig)
    wasReady = false
    rebufferCount = 0
    playbackStartTimeMs = 0
    bufferAtPlaybackStartMs = 0
    Timber.d("AutomaticBufferManager reset to defaults")
  }

  // region Player.Listener

  private var lastBufferLogTimeMs: Long = 0
  private val bufferLogIntervalMs = 2_000L

  override fun onPlaybackStateChanged(playbackState: Int) {
    when (playbackState) {
      Player.STATE_BUFFERING -> {
        if (wasReady) {
          onRebuffer()
        }
        if (bufferingStartTimeMs == 0L) {
          bufferingStartTimeMs = System.currentTimeMillis()
          Timber.d("STATE_BUFFERING entered - starting buffer timer")
        }
      }
      Player.STATE_READY -> {
        if (!wasReady) {
          playbackStartTimeMs = System.currentTimeMillis()
          bufferAtPlaybackStartMs = exoPlayer?.totalBufferedDuration ?: 0
          lastBufferLogTimeMs = playbackStartTimeMs

          val bufferingDurationMs =
            if (bufferingStartTimeMs > 0) {
              playbackStartTimeMs - bufferingStartTimeMs
            } else 0

          Timber.d(
            "Playback ready with %dms buffer (buffered for %dms)",
            bufferAtPlaybackStartMs,
            bufferingDurationMs,
          )
          bufferingStartTimeMs = 0
        }
        wasReady = true
      }
      Player.STATE_IDLE,
      Player.STATE_ENDED -> {
        wasReady = false
        playbackStartTimeMs = 0
        bufferingStartTimeMs = 0
      }
    }
  }

  override fun onIsPlayingChanged(
    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
    isPlaying: Boolean,
  ) {
    if (isPlaying) {
      startBufferMonitoring()
    } else {
      stopBufferMonitoring()
    }
  }

  private var bufferMonitorHandler: android.os.Handler? = null
  private val bufferMonitorRunnable =
    object : Runnable {
      override fun run() {
        if (wasReady && exoPlayer?.isPlaying == true) {
          val bufferedMs = exoPlayer?.totalBufferedDuration ?: 0
          val playedMs = System.currentTimeMillis() - playbackStartTimeMs
          val config = loadControl.getBufferConfig()
          Timber.d(
            "Buffer status: %dms buffered, played %dms (rebuffer threshold: %dms)",
            bufferedMs,
            playedMs,
            config.bufferForPlaybackAfterRebufferMs,
          )
          bufferMonitorHandler?.postDelayed(this, bufferLogIntervalMs)
        }
      }
    }

  private fun startBufferMonitoring() {
    if (bufferMonitorHandler == null) {
      bufferMonitorHandler = android.os.Handler(android.os.Looper.getMainLooper())
    }
    bufferMonitorHandler?.removeCallbacks(bufferMonitorRunnable)
    bufferMonitorHandler?.postDelayed(bufferMonitorRunnable, bufferLogIntervalMs)
  }

  private fun stopBufferMonitoring() {
    bufferMonitorHandler?.removeCallbacks(bufferMonitorRunnable)
  }

  override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
    if (
      reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
    ) {
      Timber.d("Media item changed, resetting buffer config")
      reset()
    }
  }

  // endregion

  // region Buffer adjustment logic

  private fun onRebuffer() {
    rebufferCount++
    val now = System.currentTimeMillis()
    val playbackDurationMs = if (playbackStartTimeMs > 0) now - playbackStartTimeMs else 0

    Timber.d(
      "Rebuffer detected (count: $rebufferCount, played for %dms with %dms initial buffer)",
      playbackDurationMs,
      bufferAtPlaybackStartMs,
    )

    if (playbackDurationMs > 0 && bufferAtPlaybackStartMs > 0) {
      val newRebufferBuffer =
        calculateOptimalRebufferBuffer(playbackDurationMs, bufferAtPlaybackStartMs)
      val currentConfig = loadControl.getBufferConfig()
      if (newRebufferBuffer != currentConfig.bufferForPlaybackAfterRebufferMs) {
        Timber.d(
          "Adjusting rebuffer threshold: %dms -> %dms",
          currentConfig.bufferForPlaybackAfterRebufferMs,
          newRebufferBuffer,
        )
        loadControl.updateBufferConfig(
          currentConfig.copy(bufferForPlaybackAfterRebufferMs = newRebufferBuffer)
        )
      }
    } else {
      Timber.d("Skipping adjustment - insufficient timing data")
    }

    // The next rebuffer counts only after STATE_READY records a fresh start.
    wasReady = false
    playbackStartTimeMs = 0
    bufferAtPlaybackStartMs = 0
  }

  /**
   * Assumes the buffer held at ready drained to empty over [playbackDurationMs], and returns the
   * threshold that would last [targetPlaybackDurationMs]: 1000ms lasting 30s needs 2000ms.
   */
  private fun calculateOptimalRebufferBuffer(playbackDurationMs: Long, initialBufferMs: Long): Int {
    val currentConfig = loadControl.getBufferConfig()

    if (playbackDurationMs <= 0 || initialBufferMs <= 0) {
      Timber.d("Insufficient timing data, using 1.5x fallback")
      return (currentConfig.bufferForPlaybackAfterRebufferMs * 1.5)
        .toInt()
        .coerceIn(defaultConfig.bufferForPlaybackAfterRebufferMs, maxRebufferBufferMs)
    }

    val drainRateMsPerSec = (initialBufferMs.toFloat() / (playbackDurationMs / 1000f))

    val neededBufferMs = (drainRateMsPerSec * (targetPlaybackDurationMs / 1000)).toLong()

    Timber.d(
      "Drain rate: %.1f ms/sec, needed buffer for 60s: %dms",
      drainRateMsPerSec,
      neededBufferMs,
    )

    // The current threshold as the floor: it never drops within an item.
    return neededBufferMs
      .toInt()
      .coerceIn(currentConfig.bufferForPlaybackAfterRebufferMs, maxRebufferBufferMs)
  }

  // endregion
}
