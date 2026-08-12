package com.audiobrowser.player

import androidx.media3.common.C
import com.audiobrowser.util.EqualizerManager
import com.margelo.nitro.audiobrowser.EqualizerSettings
import timber.log.Timber

/** The equalizer operations [EqualizerController] drives, so tests can stand in for the effect. */
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
 * Owns the equalizer's lifecycle across audio sessions. Android's Equalizer effect binds to a
 * single audioSessionId, so there are stretches with no effect at all: startup has no session until
 * the first audio output, and a session change (new ExoPlayer instance) needs a fresh one. Callers
 * see none of that — settings requested during a gap land on whichever effect comes next, and every
 * new effect announces itself through [onSettingsChanged].
 */
internal class EqualizerController(
  private val onSettingsChanged: (EqualizerSettings) -> Unit,
  private val createEffect: (Int) -> EqualizerEffect = { EqualizerManager(it) },
) {

  private var effect: EqualizerEffect? = null

  // Settings to apply to the next effect: what the caller asked for during a gap, or what the
  // outgoing effect held across a session change. A preset and custom levels are exclusive — the
  // last one asked for wins, matching how the two setters behave on a live effect.
  private var pendingPreset: String? = null
  private var pendingLevels: DoubleArray? = null
  private var pendingEnabled: Boolean? = null

  /** Initializes for [audioSessionId]; deferred when the session is still unset. */
  fun initialize(audioSessionId: Int) {
    if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
      Timber.d("Skipping equalizer init - no audio session yet, will init on first playback")
      return
    }
    install(audioSessionId)
  }

  /**
   * Reacts to an audio session change: a no-op for the same session, otherwise a fresh effect that
   * picks up the outgoing one's settings.
   */
  fun onAudioSessionChanged(newAudioSessionId: Int) {
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

  /**
   * Creates the effect for [audioSessionId] and hands it the pending settings before wiring the
   * callback, so the restore stays silent and the caller gets one event describing the result. A
   * failed create keeps the pending settings for the next attempt.
   */
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

    created.setOnSettingsChanged(onSettingsChanged)
    // Announce unconditionally. The caller reads the settings once at startup, which is exactly
    // when there may be no effect yet, and nothing else would tell it the equalizer now exists.
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
