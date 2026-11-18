package com.audiobrowser.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager

/**
 * The music stream's volume as 0.0–1.0. Registers its receiver on construction; the owner must call
 * [destroy].
 */
class SystemVolumeMonitor(private val context: Context) {
  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private var onVolumeChanged: ((Double) -> Unit)? = null

  fun getVolume(): Double = getCurrentVolume()

  /** Clamps to 0.0–1.0 and truncates to the stream's step below. */
  fun setVolume(volume: Double) {
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val newVolume = (volume.coerceIn(0.0, 1.0) * maxVolume).toInt()
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
  }

  fun setOnVolumeChanged(callback: (Double) -> Unit) {
    onVolumeChanged = callback
  }

  private fun getCurrentVolume(): Double {
    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    return if (maxVolume > 0) currentVolume.toDouble() / maxVolume else 0.0
  }

  private val volumeReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
        if (streamType == AudioManager.STREAM_MUSIC) {
          onVolumeChanged?.invoke(getCurrentVolume())
        }
      }
    }

  init {
    // Hidden AudioManager broadcast: the SDK has no public constant for it or its stream extra.
    context.registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
  }

  /**
   * Unregisters the receiver. Without it the receiver leaks across JS runtime reloads. Safe to call
   * more than once.
   */
  fun destroy() {
    try {
      context.unregisterReceiver(volumeReceiver)
    } catch (_: IllegalArgumentException) {}
  }
}
