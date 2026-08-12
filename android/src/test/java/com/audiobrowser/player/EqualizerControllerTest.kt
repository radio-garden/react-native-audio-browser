package com.audiobrowser.player

import androidx.media3.common.C
import com.margelo.nitro.audiobrowser.EqualizerSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller's contract across the stretches where no equalizer effect exists — before the
 * first audio session and across a session change. Settings asked for in a gap have to survive it,
 * and every effect that appears has to announce itself, because the JS side reads the settings once
 * at startup and otherwise never learns the equalizer arrived.
 */
class EqualizerControllerTest {

  /** Records what the controller does to an effect, and reports whatever it is told to report. */
  private class FakeEffect(override val audioSessionId: Int) : EqualizerEffect {
    var reported: EqualizerSettings? = equalizerSettings(preset = null, enabled = false)
    var callback: ((EqualizerSettings) -> Unit)? = null
    var released = false
    val presets = mutableListOf<String>()
    val levels = mutableListOf<DoubleArray>()
    val enabledCalls = mutableListOf<Boolean>()

    override fun getSettings() = reported

    override fun setOnSettingsChanged(callback: (EqualizerSettings) -> Unit) {
      this.callback = callback
    }

    override fun setEnabled(enabled: Boolean) {
      enabledCalls += enabled
    }

    override fun setPreset(presetName: String) {
      presets += presetName
    }

    override fun setLevels(levels: DoubleArray) {
      this.levels += levels
    }

    override fun release() {
      released = true
    }
  }

  private class Fixture(private val failFor: Set<Int> = emptySet()) {
    val emitted = mutableListOf<EqualizerSettings>()
    val created = mutableListOf<FakeEffect>()
    val controller =
      EqualizerController({ emitted += it }) { audioSessionId ->
        if (audioSessionId in failFor) throw RuntimeException("no effect for $audioSessionId")
        FakeEffect(audioSessionId).also { created += it }
      }
  }

  @Test
  fun `an unset session defers creation and emits nothing`() {
    val fixture = Fixture()

    fixture.controller.initialize(C.AUDIO_SESSION_ID_UNSET)

    assertTrue("created", fixture.created.isEmpty())
    assertTrue("emitted", fixture.emitted.isEmpty())
    assertNull("settings", fixture.controller.getSettings())
  }

  @Test
  fun `the first effect announces its settings`() {
    val fixture = Fixture()
    fixture.controller.initialize(C.AUDIO_SESSION_ID_UNSET)

    fixture.controller.onAudioSessionChanged(42)

    assertEquals("created", 1, fixture.created.size)
    assertEquals("emitted", 1, fixture.emitted.size)
    assertSame("emitted settings", fixture.created[0].reported, fixture.emitted[0])
  }

  @Test
  fun `settings asked for before any effect exists land on the first one`() {
    val fixture = Fixture()
    fixture.controller.initialize(C.AUDIO_SESSION_ID_UNSET)

    fixture.controller.setPreset("Rock")
    fixture.controller.setEnabled(true)
    fixture.controller.onAudioSessionChanged(42)

    val effect = fixture.created.single()
    assertEquals("presets", listOf("Rock"), effect.presets)
    assertEquals("enabled", listOf(true), effect.enabledCalls)
  }

  @Test
  fun `a preset and custom levels asked for in a gap do not both apply`() {
    val fixture = Fixture()
    fixture.controller.initialize(C.AUDIO_SESSION_ID_UNSET)

    fixture.controller.setPreset("Rock")
    fixture.controller.setLevels(doubleArrayOf(300.0, -200.0))
    fixture.controller.onAudioSessionChanged(42)

    val effect = fixture.created.single()
    assertTrue("presets", effect.presets.isEmpty())
    assertArrayEquals("levels", doubleArrayOf(300.0, -200.0), effect.levels.single(), 0.0)
  }

  @Test
  fun `restoring pending settings emits once, after the restore`() {
    val fixture = Fixture()
    fixture.controller.initialize(C.AUDIO_SESSION_ID_UNSET)
    fixture.controller.setPreset("Rock")

    fixture.controller.onAudioSessionChanged(42)

    // The effect only gets the callback once the restore is done, so the intermediate states of a
    // multi-step restore never reach the caller.
    assertEquals("emitted", 1, fixture.emitted.size)
    assertTrue("callback wired", fixture.created.single().callback != null)
  }

  @Test
  fun `a live effect takes settings directly`() {
    val fixture = Fixture()
    fixture.controller.initialize(42)

    fixture.controller.setPreset("Jazz")
    fixture.controller.setLevels(doubleArrayOf(100.0))
    fixture.controller.setEnabled(true)

    val effect = fixture.created.single()
    assertEquals("presets", listOf("Jazz"), effect.presets)
    assertEquals("levels", 1, effect.levels.size)
    assertEquals("enabled", listOf(true), effect.enabledCalls)
  }

  @Test
  fun `a session change carries the active preset to the new effect`() {
    val fixture = Fixture()
    fixture.controller.initialize(42)
    fixture.created.single().reported = equalizerSettings(preset = "Rock", enabled = true)

    fixture.controller.onAudioSessionChanged(43)

    assertTrue("old released", fixture.created[0].released)
    val fresh = fixture.created[1]
    assertEquals("session", 43, fresh.audioSessionId)
    assertEquals("presets", listOf("Rock"), fresh.presets)
    assertEquals("enabled", listOf(true), fresh.enabledCalls)
  }

  @Test
  fun `a session change carries custom levels when no preset is active`() {
    val fixture = Fixture()
    fixture.controller.initialize(42)
    fixture.created.single().reported =
      equalizerSettings(preset = null, enabled = true, levels = doubleArrayOf(400.0, 0.0))

    fixture.controller.onAudioSessionChanged(43)

    val fresh = fixture.created[1]
    assertTrue("presets", fresh.presets.isEmpty())
    assertArrayEquals("levels", doubleArrayOf(400.0, 0.0), fresh.levels.single(), 0.0)
  }

  @Test
  fun `the same session is left alone`() {
    val fixture = Fixture()
    fixture.controller.initialize(42)
    fixture.emitted.clear()

    fixture.controller.onAudioSessionChanged(42)

    assertEquals("created", 1, fixture.created.size)
    assertFalse("released", fixture.created.single().released)
    assertTrue("emitted", fixture.emitted.isEmpty())
  }

  @Test
  fun `a failed create keeps the pending settings for the next session`() {
    val fixture = Fixture(failFor = setOf(42))
    fixture.controller.initialize(C.AUDIO_SESSION_ID_UNSET)
    fixture.controller.setPreset("Rock")

    fixture.controller.onAudioSessionChanged(42)
    assertTrue("created", fixture.created.isEmpty())
    assertNull("settings", fixture.controller.getSettings())

    fixture.controller.onAudioSessionChanged(43)
    assertEquals("presets", listOf("Rock"), fixture.created.single().presets)
  }

  private companion object {
    fun equalizerSettings(
      preset: String?,
      enabled: Boolean,
      levels: DoubleArray = doubleArrayOf(0.0, 0.0),
    ) =
      EqualizerSettings(
        activePreset = preset,
        bandCount = levels.size.toDouble(),
        bandLevels = levels,
        centerBandFrequencies = DoubleArray(levels.size),
        enabled = enabled,
        lowerBandLevelLimit = -1500.0,
        presets = arrayOf("Normal", "Rock", "Jazz"),
        upperBandLevelLimit = 1500.0,
      )
  }
}
