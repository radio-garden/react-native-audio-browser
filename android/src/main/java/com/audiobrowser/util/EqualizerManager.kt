package com.audiobrowser.util

import android.media.audiofx.Equalizer
import com.margelo.nitro.audiobrowser.EqualizerSettings
import timber.log.Timber

/**
 * Manages Android Equalizer audio effect for the audio player.
 *
 * Provides access to equalizer presets, band levels, and enables/disables the effect.
 * The equalizer is attached to a specific audio session ID.
 */
class EqualizerManager(val audioSessionId: Int) {
  private var equalizer: Equalizer? = null
  private var onSettingsChanged: ((EqualizerSettings) -> Unit)? = null

  init {
    try {
      equalizer = Equalizer(0, audioSessionId).apply {
        enabled = false
      }
      Timber.d("Equalizer initialized with ${equalizer?.numberOfBands} bands")
    } catch (e: Exception) {
      Timber.e(e, "Failed to initialize equalizer")
      equalizer = null
    }
  }

  /**
   * Cleans preset name by removing null terminators and whitespace.
   * Android's Equalizer API sometimes returns preset names with trailing null characters.
   */
  private fun cleanPresetName(name: String): String {
    return name.trim().replace("\u0000", "")
  }

  /**
   * Gets the current equalizer settings.
   * @return Current settings or null if equalizer is not available
   */
  fun getSettings(): EqualizerSettings? {
    val eq = equalizer ?: return null

    return try {
      val bandCount = eq.numberOfBands.toInt()
      val bandLevels = (0 until bandCount).map { band ->
        eq.getBandLevel(band.toShort()).toDouble()
      }.toDoubleArray()

      val centerFrequencies = (0 until bandCount).map { band ->
        eq.getCenterFreq(band.toShort()).toDouble()
      }.toDoubleArray()

      val presets = (0 until eq.numberOfPresets).map { preset ->
        cleanPresetName(eq.getPresetName(preset.toShort()))
      }.toTypedArray()

      val activePreset = try {
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
        upperBandLevelLimit = eq.bandLevelRange[1].toDouble()
      )
    } catch (e: Exception) {
      Timber.e(e, "Failed to get equalizer settings")
      null
    }
  }

  /**
   * Enables or disables the equalizer.
   * @param enabled true to enable, false to disable
   */
  fun setEnabled(enabled: Boolean) {
    val eq = equalizer ?: return

    // Only notify if state actually changed
    if (eq.enabled != enabled) {
      eq.enabled = enabled
      notifySettingsChanged()
    }
  }

  /**
   * Applies a preset to the equalizer.
   * @param presetName Name of the preset to apply
   */
  fun setPreset(presetName: String) {
    val eq = equalizer ?: return

    try {
      // Get current preset first
      val currentPreset = try {
        eq.currentPreset.toInt()
      } catch (e: Exception) {
        -1
      }

      // Find preset by case-insensitive name match
      val presetIndex = (0 until eq.numberOfPresets).firstOrNull { index ->
        cleanPresetName(eq.getPresetName(index.toShort())).equals(presetName, ignoreCase = true)
      }

      if (presetIndex != null) {
        // Only apply if it's actually a different preset
        if (currentPreset != presetIndex) {
          eq.usePreset(presetIndex.toShort())
          Timber.d("Applied equalizer preset: $presetName")
          notifySettingsChanged()
        } else {
          Timber.d("Preset already active: $presetName")
        }
      } else {
        // Log available presets to help debug
        val availablePresets = (0 until eq.numberOfPresets).joinToString(", ") { index ->
          cleanPresetName(eq.getPresetName(index.toShort()))
        }
        Timber.w("Preset not found: $presetName. Available presets: $availablePresets")
      }
    } catch (e: Exception) {
      Timber.e(e, "Failed to apply preset: $presetName")
    }
  }

  /**
   * Sets custom band levels for the equalizer.
   * @param levels Array of levels in millibels for each band
   */
  fun setLevels(levels: DoubleArray) {
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

  /**
   * Sets a callback to be invoked when equalizer settings change.
   * @param callback Function to call with new settings
   */
  fun setOnSettingsChanged(callback: (EqualizerSettings) -> Unit) {
    onSettingsChanged = callback
  }

  private fun notifySettingsChanged() {
    getSettings()?.let { settings ->
      onSettingsChanged?.invoke(settings)
    }
  }

  /**
   * Releases the equalizer resources.
   * Call this when the equalizer is no longer needed.
   */
  fun release() {
    try {
      equalizer?.release()
      equalizer = null
      Timber.d("Equalizer released")
    } catch (e: Exception) {
      Timber.e(e, "Failed to release equalizer")
    }
  }
}
