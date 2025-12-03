package com.audiobrowser.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Helper for checking battery optimization status and opening settings.
 *
 * Android has two separate battery-related restrictions:
 * 1. Battery optimization whitelist (Doze exemption) - isIgnoringBatteryOptimizations
 * 2. Background restriction (hard block) - isBackgroundRestricted (API 28+)
 *
 * Background restriction is the dominant signal - if true, foreground service starts from
 * background will fail regardless of battery optimization status.
 */
object BatteryOptimizationHelper {
  enum class Status {
    /** App can run freely in background. User explicitly allowed it. */
    UNRESTRICTED,
    /** System may limit background work (Doze, App Standby). Default state. */
    OPTIMIZED,
    /** User or system severely limited background. Jobs/services blocked. */
    RESTRICTED,
  }

  /**
   * Get the current battery optimization status.
   *
   * Priority: Background restriction > Battery optimization whitelist
   */
  fun getStatus(context: Context): Status {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isIgnoring = pm.isIgnoringBatteryOptimizations(context.packageName)

    val isBackgroundRestricted =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.isBackgroundRestricted
      } else {
        false
      }

    // Background restriction is the dominant signal - if true, foreground service
    // will fail regardless of battery optimization whitelist status
    return when {
      isBackgroundRestricted -> Status.RESTRICTED
      isIgnoring -> Status.UNRESTRICTED
      else -> Status.OPTIMIZED
    }
  }

  /**
   * Open settings to let user change battery restrictions.
   *
   * For OPTIMIZED status: Shows a quick dialog to disable battery optimization (one tap). For
   * RESTRICTED status: Opens app settings where user must manually change battery setting.
   */
  fun openSettings(context: Context) {
    when (getStatus(context)) {
      Status.OPTIMIZED -> requestIgnoreBatteryOptimizations(context)
      else -> openAppSettings(context)
    }
  }

  /**
   * Show system dialog to request ignoring battery optimizations. Only effective for OPTIMIZED
   * status - won't remove background restrictions.
   */
  private fun requestIgnoreBatteryOptimizations(context: Context) {
    val intent =
      Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        data = Uri.parse("package:${context.packageName}")
      }
    context.startActivity(intent)
  }

  /**
   * Open the app's settings page where user can change battery restrictions. Required for
   * RESTRICTED status since the quick dialog won't help.
   */
  private fun openAppSettings(context: Context) {
    val intent =
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        data = Uri.fromParts("package", context.packageName, null)
      }
    context.startActivity(intent)
  }
}
