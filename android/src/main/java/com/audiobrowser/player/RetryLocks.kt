package com.audiobrowser.player

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.PowerManager
import com.margelo.nitro.audiobrowser.AndroidPlayerWakeMode

/**
 * The locks the engine's wake mode holds while it loads, held instead while [RetryLoop] keeps the
 * engine idle between connections: ExoPlayer releases its own in IDLE, and with the CPU asleep the
 * loop's reconnect and deadline would not run.
 */
internal class RetryLocks(
  context: Context,
  private val wakeMode: AndroidPlayerWakeMode,
  /** A backstop past the loop's deadline, which releases the locks first. */
  private val maxHoldMs: Long,
  private val handler: Handler,
) {
  private val wakeLock =
    (context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioBrowser:retry")
      .apply { setReferenceCounted(false) }

  @Suppress("DEPRECATION")
  private val wifiLock =
    (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?)
      ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AudioBrowser:retry")
      ?.apply { setReferenceCounted(false) }

  /** The Wi-Fi lock has no timeout of its own. */
  private val releaseWifiLock = Runnable { if (wifiLock?.isHeld == true) wifiLock.release() }

  val wakeLockHeld: Boolean
    get() = wakeLock.isHeld

  val wifiLockHeld: Boolean
    get() = wifiLock?.isHeld == true

  fun hold(held: Boolean) {
    if (held && wakeMode != AndroidPlayerWakeMode.NONE) wakeLock.acquire(maxHoldMs)
    else if (wakeLock.isHeld) wakeLock.release()
    handler.removeCallbacks(releaseWifiLock)
    if (held && wakeMode == AndroidPlayerWakeMode.NETWORK) {
      wifiLock?.acquire()
      handler.postDelayed(releaseWifiLock, maxHoldMs)
    } else {
      releaseWifiLock.run()
    }
  }
}
