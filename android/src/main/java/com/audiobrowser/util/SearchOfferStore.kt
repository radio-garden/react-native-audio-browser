package com.audiobrowser.util

import android.content.Context

/**
 * Whether the last configured browser had search, across process death: a car that connects on a
 * cold start, before the configuration, keeps the search commands it is offered then.
 */
object SearchOfferStore {
  private const val PREFS_NAME = "audio_browser_search_offer"
  private const val KEY_HAS_SEARCH = "has_search"

  fun record(context: Context, hasSearch: Boolean) {
    getPrefs(context).edit().putBoolean(KEY_HAS_SEARCH, hasSearch).apply()
  }

  /** The last [record]ed value; true before any. */
  fun lastHasSearch(context: Context): Boolean = getPrefs(context).getBoolean(KEY_HAS_SEARCH, true)

  private fun getPrefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
