package com.audiobrowser.util

import android.content.Context

/**
 * Persists foreground service start failure state using SharedPreferences.
 *
 * When Android 12+ blocks a foreground service start from background (e.g., Bluetooth resume
 * while app is killed), we record this failure so the app can show a warning to the user
 * on their next session.
 */
object BatteryWarningStore {
    private const val PREFS_NAME = "audio_browser_battery"
    private const val KEY_WARNING_PENDING = "warning_pending"

    /**
     * Record that a foreground service start failure occurred.
     * Called when ForegroundServiceStartNotAllowedException is caught.
     */
    fun recordFailure(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_WARNING_PENDING, true)
            .apply()
    }

    /**
     * Check if a warning is pending (failure occurred and not yet dismissed).
     */
    fun isWarningPending(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_WARNING_PENDING, false)

    /**
     * Clear the pending warning.
     * Called when user dismisses warning or when battery status becomes unrestricted.
     */
    fun clearWarning(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_WARNING_PENDING, false)
            .apply()
    }

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
