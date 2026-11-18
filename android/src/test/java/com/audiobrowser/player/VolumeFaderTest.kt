package com.audiobrowser.player

import android.os.Looper
import android.os.SystemClock
import java.time.Duration
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class VolumeFaderTest {

  private var volume = 0.8f
  private val fader = VolumeFader(getVolume = { volume }, setVolume = { volume = it })
  private val looper = shadowOf(Looper.getMainLooper())

  @Test
  fun `a fade started late joins the ramp where it would be`() {
    fader.start(SystemClock.uptimeMillis() + 5_000, durationSeconds = 10.0)
    looper.idle()

    assertEquals(0.8f * VolumeFader.gain(0.5), volume, 1e-6f)
  }

  @Test
  fun `the fade falls by equal decibels each second`() {
    fader.start(SystemClock.uptimeMillis() + 10_000, durationSeconds = 10.0)
    looper.idle()

    val decibels =
      (0 until 10).map {
        val level = 20 * log10(volume / 0.8)
        looper.idleFor(Duration.ofSeconds(1))
        level
      }

    decibels.zipWithNext { a, b -> assertEquals(VolumeFader.FLOOR_DECIBELS / 10, b - a, 0.01) }
    assertEquals(0f, volume, 0f)
  }
}
