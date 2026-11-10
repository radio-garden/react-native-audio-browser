package com.audiobrowser.player

import android.content.Context
import android.net.wifi.WifiManager
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

  val wakeLockHeld: Boolean
    get() = wakeLock.isHeld

  fun hold(held: Boolean) {
    if (held && wakeMode != AndroidPlayerWakeMode.NONE) wakeLock.acquire(maxHoldMs)
    else if (wakeLock.isHeld) wakeLock.release()
    if (held && wakeMode == AndroidPlayerWakeMode.NETWORK) wifiLock?.acquire()
    else if (wifiLock?.isHeld == true) wifiLock.release()
  }
}
