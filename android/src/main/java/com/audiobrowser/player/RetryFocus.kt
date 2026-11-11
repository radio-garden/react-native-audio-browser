package com.audiobrowser.player

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler

/**
 * Audio focus while [RetryLoop] keeps the engine idle between connections: media3 abandons the
 * engine's focus in IDLE, so the library holds its own and answers a change as media3 answers one
 * while playing. A permanent loss, or a request refused, is a pause; a transient loss, or a request
 * that waits (a call), suppresses the play intent until focus returns.
 */
internal class RetryFocus(
  private val port: Port,
  private val onLoss: () -> Unit,
  private val onSuppressionChanged: () -> Unit,
) {
  interface Port {
    /** Requests focus, accepting a delayed gain; returns AudioManager's result. */
    fun request(listener: AudioManager.OnAudioFocusChangeListener): Int

    fun abandon()
  }

  private var requested = false
  private var generation = 0

  /** Whether focus is lost for now, or not yet granted: the play intent stays down until it is. */
  var suppressed = false
    private set

  fun hold() {
    if (requested) return
    requested = true
    val ours = ++generation
    val listener =
      AudioManager.OnAudioFocusChangeListener { change ->
        // A change for a request since abandoned: at the reconnect, see [handOver].
        if (ours == generation) changed(change)
      }
    when (port.request(listener)) {
      AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> Unit
      AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> suppress(true)
      else -> lose()
    }
  }

  fun release() {
    if (!requested) return
    requested = false
    generation++
    port.abandon()
    suppress(false)
  }

  /**
   * Runs the reconnect's [prepare], then lets this request go. ExoPlayer requests the engine's
   * focus on its playback thread a few milliseconds later: another app's request in that gap is
   * overridden by the engine's, as by a tap on play, and its loss to this request is dropped.
   */
  fun handOver(prepare: () -> Unit) {
    prepare()
    release()
  }

  private fun changed(change: Int) {
    when (change) {
      AudioManager.AUDIOFOCUS_GAIN -> suppress(false)
      AudioManager.AUDIOFOCUS_LOSS -> lose()
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> suppress(true)
    }
  }

  private fun lose() {
    release()
    onLoss()
  }

  private fun suppress(suppressed: Boolean) {
    if (suppressed == this.suppressed) return
    this.suppressed = suppressed
    onSuppressionChanged()
  }

  /** [Port] on the platform's AudioManager, its changes delivered on [handler]. */
  class Platform(
    private val audioManager: AudioManager,
    private val handler: Handler,
    private val attributes: () -> AudioAttributes,
  ) : Port {
    private var request: AudioFocusRequest? = null
    private var legacyListener: AudioManager.OnAudioFocusChangeListener? = null

    override fun request(listener: AudioManager.OnAudioFocusChangeListener): Int =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val request =
          AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes())
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(listener, handler)
            .build()
        this.request = request
        audioManager.requestAudioFocus(request)
      } else {
        legacyListener = listener
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(
          listener,
          AudioManager.STREAM_MUSIC,
          AudioManager.AUDIOFOCUS_GAIN,
        )
      }

    override fun abandon() {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        request?.let { audioManager.abandonAudioFocusRequest(it) }
        request = null
      } else {
        @Suppress("DEPRECATION") legacyListener?.let { audioManager.abandonAudioFocus(it) }
        legacyListener = null
      }
    }
  }
}
