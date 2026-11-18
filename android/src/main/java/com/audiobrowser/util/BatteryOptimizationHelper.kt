package com.audiobrowser.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object BatteryOptimizationHelper {
  enum class Status {
    UNRESTRICTED,
    OPTIMIZED,
    RESTRICTED,
  }

  /**
   * A background restriction (API 28+) outranks a Doze exemption, so the result is then
   * [Status.RESTRICTED]: under it, foreground service starts from the background fail either way.
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

    return when {
      isBackgroundRestricted -> Status.RESTRICTED
      isIgnoring -> Status.UNRESTRICTED
      else -> Status.OPTIMIZED
    }
  }

  /**
   * When [Status.OPTIMIZED], shows the system's one-tap exemption dialog; otherwise opens the app's
   * settings page, since the dialog cannot lift a background restriction.
   */
  fun openSettings(context: Context) {
    when (getStatus(context)) {
      Status.OPTIMIZED -> requestIgnoreBatteryOptimizations(context)
      else -> openAppSettings(context)
    }
  }

  private fun requestIgnoreBatteryOptimizations(context: Context) {
    val intent =
      Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        data = Uri.parse("package:${context.packageName}")
      }
    context.startActivity(intent)
  }

  private fun openAppSettings(context: Context) {
    val intent =
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        data = Uri.fromParts("package", context.packageName, null)
      }
    context.startActivity(intent)
  }
}
