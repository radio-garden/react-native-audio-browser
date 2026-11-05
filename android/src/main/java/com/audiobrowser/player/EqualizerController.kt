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
 * session, so there is none before the first audio output and a fresh one after a session change;
 * settings asked for meanwhile apply to the next effect, and each new effect reports its settings
 * through [onSettingsChanged].
 */
internal class EqualizerController(
  private val onSettingsChanged: (EqualizerSettings) -> Unit,
  private val createEffect: (Int) -> EqualizerEffect = { EqualizerManager(it) },
) {

  private var effect: EqualizerEffect? = null

  // For the next effect. A preset and custom levels exclude each other: the last one set wins, as
  // on a live effect.
  private var pendingPreset: String? = null
  private var pendingLevels: DoubleArray? = null
  private var pendingEnabled: Boolean? = null

  /** An unset session defers the effect to the first [onAudioSessionChanged]. */
  fun initialize(audioSessionId: Int) {
    if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
      Timber.d("Skipping equalizer init - no audio session yet, will init on first playback")
      return
    }
    install(audioSessionId)
  }

  /**
   * Moves the settings to a fresh effect on [newAudioSessionId]; the same session is a no-op. An
   * unset session (playback on a remote output) keeps the effect on the local session, which it
   * equalizes again on the way back: an effect on session 0 would equalize the whole device.
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
        // The pending settings stay for the next attempt.
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
    // Always announced: the caller reads the settings once at startup, often before any effect
    // exists, and nothing else tells it one does now.
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
