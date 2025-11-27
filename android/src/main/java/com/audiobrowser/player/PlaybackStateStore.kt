package com.audiobrowser.player

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.common.C
import com.margelo.nitro.audiobrowser.RepeatMode
import timber.log.Timber

/**
 * Persists playback state for resumption after app restart. Stores the current track URL, position,
 * and player settings to enable playback resumption via MediaButtonReceiver (Bluetooth play button,
 * etc.).
 */
class PlaybackStateStore(private val player: Player) {
  private val prefs: SharedPreferences =
    player.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /**
   * Persisted playback state for resumption.
   *
   * @param url The contextual URL of the track
   * @param positionMs The playback position in milliseconds (C.TIME_UNSET for default/live edge)
   * @param repeatMode The repeat mode setting
   * @param shuffleEnabled Whether shuffle mode was enabled
   * @param playbackSpeed The playback speed (1.0 = normal)
   */
  data class PersistedState(
    val url: String,
    val positionMs: Long,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    val playbackSpeed: Float,
  )

  /**
   * Saves the current playback state for later resumption. Reads all state directly from the
   * player.
   *
   * For live streams, position is stored as C.TIME_UNSET to signal "use default position", which
   * Media3 interprets as the live edge.
   */
  fun save() {
    val url = player.exoPlayer.currentMediaItem?.mediaId ?: return

    // Use TIME_UNSET for live streams - Media3 will seek to live edge
    val positionMs = if (player.isCurrentItemLive) C.TIME_UNSET else player.position

    prefs.edit {
      putString(KEY_URL, url)
      putLong(KEY_POSITION_MS, positionMs)
      putLong(KEY_TIMESTAMP, System.currentTimeMillis())
      putString(KEY_REPEAT_MODE, player.repeatMode.name)
      putBoolean(KEY_SHUFFLE_ENABLED, player.shuffleMode)
      putFloat(KEY_PLAYBACK_SPEED, player.playbackSpeed)
    }
    Timber.d(
      "Saved playback state: url=$url, positionMs=$positionMs, repeatMode=${player.repeatMode}, shuffle=${player.shuffleMode}, speed=${player.playbackSpeed}"
    )
  }

  /**
   * Restores player settings from persisted state and returns the state for queue setup.
   *
   * Applies repeatMode, shuffleMode, and playbackSpeed to the player. The caller is responsible
   * for using the returned url and positionMs to set up the playback queue.
   *
   * @return PersistedState if available, null otherwise
   */
  fun restore(): PersistedState? =
    get()?.also { state ->
      // Apply player settings
      player.repeatMode = state.repeatMode
      player.shuffleMode = state.shuffleEnabled
      player.playbackSpeed = state.playbackSpeed

      Timber.d(
        "Restored player settings: repeatMode=${state.repeatMode}, shuffle=${state.shuffleEnabled}, speed=${state.playbackSpeed}"
      )
    }

  /**
   * Retrieves the persisted playback state.
   *
   * @return PersistedState if available, null otherwise
   */
  fun get(): PersistedState? {
    val url = prefs.getString(KEY_URL, null) ?: return null
    val positionMs = prefs.getLong(KEY_POSITION_MS, 0)
    val repeatMode =
      prefs.getString(KEY_REPEAT_MODE, null)?.let { name ->
        runCatching { RepeatMode.valueOf(name) }.getOrNull()
      } ?: RepeatMode.OFF
    val shuffleEnabled = prefs.getBoolean(KEY_SHUFFLE_ENABLED, false)
    val playbackSpeed = prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
    return PersistedState(url, positionMs, repeatMode, shuffleEnabled, playbackSpeed)
  }

  /** Clears the persisted playback state. */
  fun clear() {
    prefs.edit { clear() }
    Timber.d("Cleared playback state")
  }

  companion object {
    // Versioned key to avoid backwards compatibility issues (alpha product)
    private const val PREFS_NAME = "audio_browser_playback_state_v1"
    private const val KEY_URL = "url"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
    private const val KEY_PLAYBACK_SPEED = "playback_speed"
  }
}
