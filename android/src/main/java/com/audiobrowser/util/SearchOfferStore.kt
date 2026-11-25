package com.audiobrowser.util

import android.content.Context

/**
 * Whether the last configured browser had search, across process death: a car that connects on a
 * cold start, before the configuration, keeps the search commands it is offered then.
 */
object SearchOfferStore {
  private const val PREFS_NAME = "audio_browser_search_offer"
  private const val KEY_HAS_SEARCH = "has_search"
  private const val KEY_LAST_UPDATE_TIME = "last_update_time"

  fun record(context: Context, hasSearch: Boolean) {
    getPrefs(context)
      .edit()
      .putBoolean(KEY_HAS_SEARCH, hasSearch)
      .putLong(KEY_LAST_UPDATE_TIME, lastUpdateTime(context))
      .apply()
  }

  /**
   * The value this install of the app last [record]ed; true before any, and for a value an earlier
   * install recorded, as an update may add search.
   */
  fun lastHasSearch(context: Context): Boolean {
    val prefs = getPrefs(context)
    if (prefs.getLong(KEY_LAST_UPDATE_TIME, -1) != lastUpdateTime(context)) return true
    return prefs.getBoolean(KEY_HAS_SEARCH, true)
  }

  private fun lastUpdateTime(context: Context): Long =
    context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

  private fun getPrefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
