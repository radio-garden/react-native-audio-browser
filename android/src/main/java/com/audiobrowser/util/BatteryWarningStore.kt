package com.audiobrowser.util

import android.content.Context

/**
 * Remembers, across process death, that Android 12+ refused to start the foreground service from
 * the background (`ForegroundServiceStartNotAllowedException`, e.g. a Bluetooth resume while the
 * app is killed), so the app can warn the user on its next session.
 */
object BatteryWarningStore {
  private const val PREFS_NAME = "audio_browser_battery"
  private const val KEY_WARNING_PENDING = "warning_pending"

  fun recordFailure(context: Context) {
    getPrefs(context).edit().putBoolean(KEY_WARNING_PENDING, true).apply()
  }

  /** True from [recordFailure] until [clearWarning]. */
  fun isWarningPending(context: Context): Boolean =
    getPrefs(context).getBoolean(KEY_WARNING_PENDING, false)

  fun clearWarning(context: Context) {
    getPrefs(context).edit().putBoolean(KEY_WARNING_PENDING, false).apply()
  }

  private fun getPrefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
