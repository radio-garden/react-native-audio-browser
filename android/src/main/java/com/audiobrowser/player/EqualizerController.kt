package com.audiobrowser.player

import androidx.media3.common.C
import com.audiobrowser.util.EqualizerManager
import com.margelo.nitro.audiobrowser.EqualizerSettings
import timber.log.Timber

internal interface EqualizerEffect {
  val audioSessionId: Int

  fun getSettings(): EqualizerSettings?

  fun setOnSettingsChanged(callback: (EqualizerSettings) -> Unit)

  fun setEnabled(enabled: Boolean)

  fun setPreset(presetName: String)

  fun setLevels(levels: DoubleArray)

  fun release()
}

/**
 * Keeps one equalizer effect on the player's current audio session. An effect binds to a single
 * session, so settings asked for while there is none apply to the next effect.
 */
internal class EqualizerController(
  private val onSettingsChanged: (EqualizerSettings) -> Unit,
  private val createEffect: (Int) -> EqualizerEffect = { EqualizerManager(it) },
) {

  private var effect: EqualizerEffect? = null

  private var pendingPreset: String? = null
  private var pendingLevels: DoubleArray? = null
  private var pendingEnabled: Boolean? = null

  fun initialize(audioSessionId: Int) {
    if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
      Timber.d("Skipping equalizer init - no audio session yet, will init on first playback")
      return
    }
    install(audioSessionId)
  }

  /**
   * Moves the settings to a fresh effect on [newAudioSessionId]. An unset session (playback on a
   * remote output) keeps the current effect: one on session 0 would equalize the whole device.
   */
  fun onAudioSessionChanged(newAudioSessionId: Int) {
    if (newAudioSessionId == C.AUDIO_SESSION_ID_UNSET) return
    val old = effect
    if (old != null && old.audioSessionId == newAudioSessionId) return

    old?.let {
      it.getSettings()?.let(::remember)
      it.release()
      effect = null
    }
    install(newAudioSessionId)
  }

  fun release() {
    effect?.release()
    effect = null
  }

  /** Null while there is no effect, even when settings are pending. */
  fun getSettings(): EqualizerSettings? = effect?.getSettings()

  fun setEnabled(enabled: Boolean) {
    val effect = effect
    if (effect == null) {
      pendingEnabled = enabled
      return
    }
    effect.setEnabled(enabled)
  }

  fun setPreset(preset: String) {
    val effect = effect
    if (effect == null) {
      rememberPreset(preset)
      return
    }
    effect.setPreset(preset)
  }

  fun setLevels(levels: DoubleArray) {
    val effect = effect
    if (effect == null) {
      rememberLevels(levels)
      return
    }
    effect.setLevels(levels)
  }

  private fun install(audioSessionId: Int) {
    val created =
      try {
        createEffect(audioSessionId)
      } catch (e: Exception) {
        Timber.e(e, "Failed to initialize equalizer")
        return
      }
    effect = created
    Timber.d("Equalizer initialized with session ID: $audioSessionId")

    pendingPreset?.let(created::setPreset)
    pendingLevels?.let(created::setLevels)
    pendingEnabled?.let(created::setEnabled)
    pendingPreset = null
    pendingLevels = null
    pendingEnabled = null

    // Wired after the restore, so its intermediate steps never reach the caller.
    created.setOnSettingsChanged(onSettingsChanged)
    // The caller may have read the settings before any effect existed.
    created.getSettings()?.let(onSettingsChanged)
  }

  private fun remember(settings: EqualizerSettings) {
    val preset = settings.activePreset
    if (preset != null) {
      rememberPreset(preset)
    } else {
      rememberLevels(settings.bandLevels)
    }
    pendingEnabled = settings.enabled
  }

  private fun rememberPreset(preset: String) {
    pendingPreset = preset
    pendingLevels = null
  }

  private fun rememberLevels(levels: DoubleArray) {
    pendingLevels = levels
    pendingPreset = null
  }
}
