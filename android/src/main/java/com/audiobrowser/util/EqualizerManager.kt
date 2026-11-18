package com.audiobrowser.util

import android.media.audiofx.Equalizer
import com.audiobrowser.player.EqualizerEffect
import com.margelo.nitro.audiobrowser.EqualizerSettings
import timber.log.Timber

/**
 * The platform [Equalizer] on one audio session. Where the device refuses to create one, every
 * setter is a no-op and [getSettings] returns null.
 */
internal class EqualizerManager(override val audioSessionId: Int) : EqualizerEffect {
  private var equalizer: Equalizer? = null
  private var onSettingsChanged: ((EqualizerSettings) -> Unit)? = null

  init {
    try {
      equalizer = Equalizer(0, audioSessionId).apply { enabled = false }
      Timber.d("Equalizer initialized with ${equalizer?.numberOfBands} bands")
    } catch (e: Exception) {
      Timber.e(e, "Failed to initialize equalizer")
      equalizer = null
    }
  }

  // Some devices return preset names with trailing NUL characters.
  private fun cleanPresetName(name: String): String {
    return name.trim().replace("\u0000", "")
  }

  override fun getSettings(): EqualizerSettings? {
    val eq = equalizer ?: return null

    return try {
      val bandCount = eq.numberOfBands.toInt()
      val bandLevels =
        (0 until bandCount)
          .map { band -> eq.getBandLevel(band.toShort()).toDouble() }
          .toDoubleArray()

      val centerFrequencies =
        (0 until bandCount)
          .map { band -> eq.getCenterFreq(band.toShort()).toDouble() }
          .toDoubleArray()

      val presets =
        (0 until eq.numberOfPresets)
          .map { preset -> cleanPresetName(eq.getPresetName(preset.toShort())) }
          .toTypedArray()

      val activePreset =
        try {
          val currentPreset = eq.currentPreset.toInt()
          if (currentPreset >= 0) {
            cleanPresetName(eq.getPresetName(currentPreset.toShort()))
          } else {
            null
          }
        } catch (e: Exception) {
          null
        }

      EqualizerSettings(
        activePreset = activePreset,
        bandCount = bandCount.toDouble(),
        bandLevels = bandLevels,
        centerBandFrequencies = centerFrequencies,
        enabled = eq.enabled,
        lowerBandLevelLimit = eq.bandLevelRange[0].toDouble(),
        presets = presets,
        upperBandLevelLimit = eq.bandLevelRange[1].toDouble(),
      )
    } catch (e: Exception) {
      Timber.e(e, "Failed to get equalizer settings")
      null
    }
  }

  override fun setEnabled(enabled: Boolean) {
    val eq = equalizer ?: return

    if (eq.enabled != enabled) {
      eq.enabled = enabled
      notifySettingsChanged()
    }
  }

  /** Matches [presetName] case-insensitively; an unknown name is logged and changes nothing. */
  override fun setPreset(presetName: String) {
    val eq = equalizer ?: return

    try {
      val presetIndex =
        (0 until eq.numberOfPresets).firstOrNull { index ->
          cleanPresetName(eq.getPresetName(index.toShort())).equals(presetName, ignoreCase = true)
        }

      if (presetIndex != null) {
        // Applied even when it is already the current preset: usePreset costs nothing then,
        // and the caller gets an event whatever the effect reports as current.
        eq.usePreset(presetIndex.toShort())
        Timber.d("Applied equalizer preset: $presetName")
        notifySettingsChanged()
      } else {
        val availablePresets =
          (0 until eq.numberOfPresets).joinToString(", ") { index ->
            cleanPresetName(eq.getPresetName(index.toShort()))
          }
        Timber.w("Preset not found: $presetName. Available presets: $availablePresets")
      }
    } catch (e: Exception) {
      Timber.e(e, "Failed to apply preset: $presetName")
    }
  }

  /** [levels] are millibels, one per band; an array of any other length changes nothing. */
  override fun setLevels(levels: DoubleArray) {
    val eq = equalizer ?: return

    try {
      val bandCount = eq.numberOfBands.toInt()
      if (levels.size != bandCount) {
        Timber.w("Level array size ${levels.size} does not match band count $bandCount")
        return
      }

      levels.forEachIndexed { index, level ->
        eq.setBandLevel(index.toShort(), level.toInt().toShort())
      }

      Timber.d("Applied custom equalizer levels")
      notifySettingsChanged()
    } catch (e: Exception) {
      Timber.e(e, "Failed to apply custom levels")
    }
  }

  override fun setOnSettingsChanged(callback: (EqualizerSettings) -> Unit) {
    onSettingsChanged = callback
  }

  private fun notifySettingsChanged() {
    getSettings()?.let { settings -> onSettingsChanged?.invoke(settings) }
  }

  override fun release() {
    try {
      equalizer?.release()
      equalizer = null
      Timber.d("Equalizer released")
    } catch (e: Exception) {
      Timber.e(e, "Failed to release equalizer")
    }
  }
}
