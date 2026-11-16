package com.audiobrowser.player

import android.os.Looper
import android.os.SystemClock
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
class SleepTimerTest {

  private val looper = shadowOf(Looper.getMainLooper())
  private var fadeArgs: Pair<Long, Double>? = null
  private val timer =
    object : SleepTimer() {
      override fun onFadeStart(deadlineUptimeMs: Long, durationSeconds: Double) {
        fadeArgs = deadlineUptimeMs to durationSeconds
      }
    }

  @Test
  fun `the fade starts its duration before the deadline`() {
    val armedAt = SystemClock.uptimeMillis()
    timer.sleepAfter(20.0, fadeDuration = 10.0)

    looper.idleFor(Duration.ofMillis(9_999))
    assertNull(fadeArgs)

    looper.idleFor(Duration.ofMillis(1))
    assertEquals(armedAt + 20_000 to 10.0, fadeArgs)
  }

  @Test
  fun `a fade that runs late still ends at the armed deadline`() {
    val armedAt = SystemClock.uptimeMillis()
    timer.sleepAfter(20.0, fadeDuration = 10.0)

    ShadowSystemClock.advanceBy(Duration.ofSeconds(15))
    looper.idle()

    assertEquals(armedAt + 20_000 to 10.0, fadeArgs)
  }
}
