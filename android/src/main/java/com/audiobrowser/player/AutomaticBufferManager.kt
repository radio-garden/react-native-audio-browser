package com.audiobrowser.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import timber.log.Timber

/**
 * Manages automatic buffer sizing based on network conditions and rebuffer events.
 *
 * This manager monitors playback state to automatically adjust buffer thresholds
 * when rebuffering occurs. The goal is to maintain smooth playback by increasing
 * buffer sizes based on measured drain rate.
 *
 * Algorithm:
 * 1. Track when playback becomes ready (buffer filled)
 * 2. Track how long until rebuffer occurs
 * 3. Calculate drain rate: initialBuffer / timeUntilRebuffer
 * 4. Calculate needed buffer to sustain 60s: drainRate × 60
 * 5. Reset to defaults when loading new media (station change)
 *
 * Target: Sustain 60 seconds of playback without rebuffering.
 */
class AutomaticBufferManager(
    private val loadControl: DynamicLoadControl,
    private val defaultConfig: BufferConfig = BufferConfig()
) : AnalyticsListener, Player.Listener {

    private var exoPlayer: ExoPlayer? = null

    // State tracking for rebuffer detection
    private var wasReady = false
    private var rebufferCount = 0

    // Timing tracking for drain rate calculation
    private var playbackStartTimeMs: Long = 0
    private var bufferAtPlaybackStartMs: Long = 0
    private var bufferingStartTimeMs: Long = 0

    // Limits - keep these reasonable for good UX
    private val maxRebufferBufferMs = 8_000 // 8 seconds max after rebuffer
    private val targetPlaybackDurationMs = 60_000L // Target: sustain 60s of playback

    /**
     * Attaches this manager to an ExoPlayer instance.
     * Call this after creating the player.
     */
    fun attach(player: ExoPlayer) {
        exoPlayer = player
        player.addAnalyticsListener(this)
        player.addListener(this)
        Timber.d("AutomaticBufferManager attached to player")
    }

    /**
     * Detaches this manager from the player.
     * Call this before releasing the player.
     */
    fun detach() {
        stopBufferMonitoring()
        bufferMonitorHandler = null
        exoPlayer?.removeAnalyticsListener(this)
        exoPlayer?.removeListener(this)
        exoPlayer = null
        Timber.d("AutomaticBufferManager detached from player")
    }

    /**
     * Resets buffer configuration to defaults and clears tracking state.
     * Call this when loading new media (station change).
     */
    fun reset() {
        loadControl.resetToDefaults()
        wasReady = false
        rebufferCount = 0
        playbackStartTimeMs = 0
        bufferAtPlaybackStartMs = 0
        Timber.d("AutomaticBufferManager reset to defaults")
    }

    // region Player.Listener

    // For periodic buffer logging during playback
    private var lastBufferLogTimeMs: Long = 0
    private val bufferLogIntervalMs = 2_000L // Log every 2 seconds

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                if (wasReady) {
                    // This is a rebuffer - buffer ran out during playback
                    onRebuffer()
                }
                // Track when buffering started
                if (bufferingStartTimeMs == 0L) {
                    bufferingStartTimeMs = System.currentTimeMillis()
                    Timber.d("STATE_BUFFERING entered - starting buffer timer")
                }
            }
            Player.STATE_READY -> {
                if (!wasReady) {
                    // Playback just became ready - record timing for drain rate calculation
                    playbackStartTimeMs = System.currentTimeMillis()
                    bufferAtPlaybackStartMs = exoPlayer?.totalBufferedDuration ?: 0
                    lastBufferLogTimeMs = playbackStartTimeMs

                    val bufferingDurationMs = if (bufferingStartTimeMs > 0) {
                        playbackStartTimeMs - bufferingStartTimeMs
                    } else 0

                    Timber.d("Playback ready with %dms buffer (buffered for %dms)",
                        bufferAtPlaybackStartMs, bufferingDurationMs)
                    bufferingStartTimeMs = 0
                }
                wasReady = true
            }
            Player.STATE_IDLE, Player.STATE_ENDED -> {
                wasReady = false
                playbackStartTimeMs = 0
                bufferingStartTimeMs = 0
            }
        }
    }

    // Called frequently during playback - use for buffer monitoring
    override fun onIsPlayingChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, isPlaying: Boolean) {
        if (isPlaying) {
            startBufferMonitoring()
        } else {
            stopBufferMonitoring()
        }
    }

    private var bufferMonitorHandler: android.os.Handler? = null
    private val bufferMonitorRunnable = object : Runnable {
        override fun run() {
            if (wasReady && exoPlayer?.isPlaying == true) {
                val bufferedMs = exoPlayer?.totalBufferedDuration ?: 0
                val playedMs = System.currentTimeMillis() - playbackStartTimeMs
                val config = loadControl.getBufferConfig()
                Timber.d("Buffer status: %dms buffered, played %dms (rebuffer threshold: %dms)",
                    bufferedMs, playedMs, config.bufferForPlaybackAfterRebufferMs)
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

    override fun onMediaItemTransition(
        mediaItem: androidx.media3.common.MediaItem?,
        reason: Int
    ) {
        // Reset on station change (new media loaded)
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
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

        Timber.d("Rebuffer detected (count: $rebufferCount, played for %dms with %dms initial buffer)",
            playbackDurationMs, bufferAtPlaybackStartMs)

        // Only adjust if we have valid timing data
        if (playbackDurationMs > 0 && bufferAtPlaybackStartMs > 0) {
            val newRebufferBuffer = calculateOptimalRebufferBuffer(playbackDurationMs, bufferAtPlaybackStartMs)
            val currentConfig = loadControl.getBufferConfig()
            if (newRebufferBuffer != currentConfig.bufferForPlaybackAfterRebufferMs) {
                Timber.d("Adjusting rebuffer threshold: %dms -> %dms",
                    currentConfig.bufferForPlaybackAfterRebufferMs, newRebufferBuffer)
                loadControl.updateBufferConfig(
                    currentConfig.copy(bufferForPlaybackAfterRebufferMs = newRebufferBuffer)
                )
            }
        } else {
            Timber.d("Skipping adjustment - insufficient timing data")
        }

        // Reset timing and mark not ready (wait for STATE_READY before counting next rebuffer)
        wasReady = false
        playbackStartTimeMs = 0
        bufferAtPlaybackStartMs = 0
    }

    /**
     * Calculates optimal rebuffer threshold based on drain rate.
     *
     * Drain rate = initialBuffer / timeUntilRebuffer
     * Needed buffer = drainRate × targetDuration (60s)
     *
     * Example: 1000ms buffer lasted 30s → drain rate = 33ms/s → need 2000ms for 60s
     */
    private fun calculateOptimalRebufferBuffer(playbackDurationMs: Long, initialBufferMs: Long): Int {
        val currentConfig = loadControl.getBufferConfig()

        // If we don't have good timing data, fall back to 1.5x increase
        if (playbackDurationMs <= 0 || initialBufferMs <= 0) {
            Timber.d("Insufficient timing data, using 1.5x fallback")
            return (currentConfig.bufferForPlaybackAfterRebufferMs * 1.5).toInt()
                .coerceIn(defaultConfig.bufferForPlaybackAfterRebufferMs, maxRebufferBufferMs)
        }

        // Calculate drain rate: how much buffer we lose per second of playback
        // drainRate = initialBuffer / playbackDuration (ms per second)
        val drainRateMsPerSec = (initialBufferMs.toFloat() / (playbackDurationMs / 1000f))

        // Calculate buffer needed to sustain 60 seconds
        val neededBufferMs = (drainRateMsPerSec * (targetPlaybackDurationMs / 1000)).toLong()

        Timber.d("Drain rate: %.1f ms/sec, needed buffer for 60s: %dms", drainRateMsPerSec, neededBufferMs)

        // Only increase threshold, never decrease (coerceIn ensures this)
        return neededBufferMs.toInt()
            .coerceIn(currentConfig.bufferForPlaybackAfterRebufferMs, maxRebufferBufferMs)
    }

    // endregion
}
