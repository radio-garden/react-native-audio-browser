package com.audiobrowser.player

import androidx.media3.common.C
import com.audiobrowser.util.EqualizerManager
import com.margelo.nitro.audiobrowser.EqualizerSettings
import timber.log.Timber

/**
 * Owns the equalizer's lifecycle across audio sessions. Android's Equalizer effect binds to a
 * single audioSessionId, so a session change (new ExoPlayer instance, first audio output) needs a
 * fresh [EqualizerManager] with the previous settings carried over — and startup may have no
 * session yet, deferring initialization to the first session change. One create-and-wire definition
 * replaces the three copies Player carried.
 */
internal class EqualizerController(private val onSettingsChanged: (EqualizerSettings) -> Unit) {

  private var manager: EqualizerManager? = null

  /** Initializes for [audioSessionId]; deferred when the session is still unset. */
  fun initialize(audioSessionId: Int) {
    if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
      Timber.d("Skipping equalizer init - no audio session yet, will init on first playback")
      return
    }
    manager = create(audioSessionId)
  }

  /**
   * Reacts to an audio session change: first-time initialization when startup deferred it, a no-op
   * for the same session, otherwise a fresh instance with the old one's settings restored.
   */
  fun onAudioSessionChanged(newAudioSessionId: Int) {
    val old = manager
    if (old == null) {
      Timber.d("First-time equalizer initialization with session ID: $newAudioSessionId")
      manager = create(newAudioSessionId)
      return
    }
    if (old.audioSessionId == newAudioSessionId) return

    try {
      // Capture current settings before releasing the old equalizer, then restore them onto the
      // new instance (a preset wins over custom levels; the enabled state always carries over).
      val settings = old.getSettings()
      old.release()
      manager = create(newAudioSessionId)
      settings?.let {
        if (it.activePreset != null) {
          manager?.setPreset(it.activePreset)
        } else if (it.enabled) {
          manager?.setLevels(it.bandLevels)
        }
        manager?.setEnabled(it.enabled)
      }
      Timber.d("Equalizer reinitialized for new session ID: $newAudioSessionId")
    } catch (e: Exception) {
      Timber.e(e, "Failed to reinitialize equalizer")
      manager = null
    }
  }

  fun release() {
    manager?.release()
    manager = null
  }

  fun getSettings(): EqualizerSettings? = manager?.getSettings()

  fun setEnabled(enabled: Boolean) {
    manager?.setEnabled(enabled)
  }

  fun setPreset(preset: String) {
    manager?.setPreset(preset)
  }

  fun setLevels(levels: DoubleArray) {
    manager?.setLevels(levels)
  }

  private fun create(audioSessionId: Int): EqualizerManager? =
    try {
      EqualizerManager(audioSessionId)
        .apply { setOnSettingsChanged(onSettingsChanged) }
        .also { Timber.d("Equalizer initialized with session ID: $audioSessionId") }
    } catch (e: Exception) {
      Timber.e(e, "Failed to initialize equalizer")
      null
    }
}
