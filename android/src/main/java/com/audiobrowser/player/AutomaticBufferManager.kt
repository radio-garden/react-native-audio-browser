package com.audiobrowser.player

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import timber.log.Timber

/**
 * Raises [DynamicLoadControl]'s `bufferForPlaybackAfterRebufferMs` after each underrun, to what the
 * measured drain rate says would last [targetPlaybackDurationMs]. The threshold only rises, up to
 * [maxRebufferBufferMs], until a playlist change or a seek to another item resets it to
 * [defaultConfig].
 *
 * An underrun is a rebuffer while every load delivers, however slowly: a connection that stays open
 * and stalls counts. A rebuffer while media3 retries a failed load in place does not, nor does a
 * reconnect's first buffering, which starts from idle.
 */
class AutomaticBufferManager(
  private val loadControl: DynamicLoadControl,
  private val defaultConfig: BufferConfig = BufferConfig(),
  private val clock: () -> Long = SystemClock::elapsedRealtime,
) : AnalyticsListener, Player.Listener {

  private var exoPlayer: ExoPlayer? = null

  private var wasReady = false
  private var rebufferCount = 0

  /** When each of the current item's loads last failed or retried, until it completes. */
  private val failingLoads = mutableMapOf<Long, Long>()

  /**
   * How long a load counts as failing after its last error or retry: longer than one attempt can
   * take to fail, a connect and then a read, each with an 8 s timeout. A progressive load that
   * recovers never completes until its file ends.
   */
  private val failingLoadWindowMs = 20_000L

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
          if (loadsFailing()) onFailedLoadRebuffer() else onRebuffer()
        }
        if (bufferingStartTimeMs == 0L) {
          bufferingStartTimeMs = clock()
          Timber.d("STATE_BUFFERING entered - starting buffer timer")
        }
      }
      Player.STATE_READY -> {
        // Playing again: the loads that failed deliver.
        failingLoads.clear()
        if (!wasReady) {
          playbackStartTimeMs = clock()
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

  override fun onLoadError(
    eventTime: AnalyticsListener.EventTime,
    loadEventInfo: LoadEventInfo,
    mediaLoadData: MediaLoadData,
    error: IOException,
    wasCanceled: Boolean,
  ) {
    // A preload of the next item failing does not starve this one. A canceled load counts too:
    // media3 cancels the load of an HLS variant it excludes, and loads another.
    if (eventTime.windowIndex != eventTime.currentWindowIndex) return
    failingLoads[loadEventInfo.loadTaskId] = clock()
  }

  override fun onLoadStarted(
    eventTime: AnalyticsListener.EventTime,
    loadEventInfo: LoadEventInfo,
    mediaLoadData: MediaLoadData,
    retryCount: Int,
  ) {
    if (loadEventInfo.loadTaskId in failingLoads) failingLoads[loadEventInfo.loadTaskId] = clock()
  }

  private fun loadsFailing(): Boolean {
    val now = clock()
    return failingLoads.values.any { now - it < failingLoadWindowMs }
  }

  override fun onLoadCompleted(
    eventTime: AnalyticsListener.EventTime,
    loadEventInfo: LoadEventInfo,
    mediaLoadData: MediaLoadData,
  ) {
    failingLoads -= loadEventInfo.loadTaskId
  }

  override fun onLoadCanceled(
    eventTime: AnalyticsListener.EventTime,
    loadEventInfo: LoadEventInfo,
    mediaLoadData: MediaLoadData,
  ) {
    failingLoads -= loadEventInfo.loadTaskId
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
          val playedMs = clock() - playbackStartTimeMs
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

  private fun onFailedLoadRebuffer() {
    Timber.d("Rebuffer while a failed load retries, threshold kept")
    restartMeasurement()
  }

  private fun onRebuffer() {
    rebufferCount++
    val now = clock()
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

    restartMeasurement()
  }

  /** The next rebuffer counts only after STATE_READY records a fresh start. */
  private fun restartMeasurement() {
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
