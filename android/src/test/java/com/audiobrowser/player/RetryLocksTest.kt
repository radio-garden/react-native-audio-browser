package com.audiobrowser.player

import android.os.Handler
import android.os.Looper
import com.margelo.nitro.audiobrowser.AndroidPlayerWakeMode
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RetryLocksTest {
  private val locks =
    RetryLocks(
      RuntimeEnvironment.getApplication(),
      AndroidPlayerWakeMode.NETWORK,
      maxHoldMs = 1_000,
      Handler(Looper.getMainLooper()),
    )

  @Test
  fun `the Wi-Fi lock is let go past the backstop if nothing releases it`() {
    locks.hold(true)
    assertTrue(locks.wifiLockHeld)

    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))

    assertFalse(locks.wifiLockHeld)
  }

  @Test
  fun `a hold that starts again moves the backstop`() {
    locks.hold(true)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
    locks.hold(false)
    locks.hold(true)

    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

    assertTrue(locks.wifiLockHeld)
  }
}
