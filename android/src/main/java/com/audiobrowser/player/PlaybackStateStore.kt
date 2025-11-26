package com.audiobrowser.player

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber

/**
 * Persists playback state for resumption after app restart. Stores the current track URL and
 * position to enable playback resumption via MediaButtonReceiver (Bluetooth play button, etc.).
 */
class PlaybackStateStore(context: Context) {
  private val prefs: SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /** Persisted playback state for resumption. */
  data class PersistedState(val url: String, val positionMs: Long)

  /**
   * Saves the current playback state for later resumption.
   *
   * @param url The contextual URL of the currently playing track
   * @param positionMs The current playback position in milliseconds
   */
  fun save(url: String, positionMs: Long) {
    prefs
      .edit()
      .putString(KEY_URL, url)
      .putLong(KEY_POSITION_MS, positionMs)
      .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
      .apply()
    Timber.d("Saved playback state: url=$url, positionMs=$positionMs")
  }

  /**
   * Retrieves the persisted playback state.
   *
   * @return PersistedState if available, null otherwise
   */
  fun get(): PersistedState? {
    val url = prefs.getString(KEY_URL, null) ?: return null
    val positionMs = prefs.getLong(KEY_POSITION_MS, 0)
    return PersistedState(url, positionMs)
  }

  /** Clears the persisted playback state. */
  fun clear() {
    prefs.edit().clear().apply()
    Timber.d("Cleared playback state")
  }

  companion object {
    private const val PREFS_NAME = "audio_browser_playback_state"
    private const val KEY_URL = "url"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_TIMESTAMP = "timestamp"
  }
}
