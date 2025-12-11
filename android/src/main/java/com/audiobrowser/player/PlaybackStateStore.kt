package com.audiobrowser.player

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.common.C
import com.margelo.nitro.audiobrowser.RepeatMode
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

/**
 * Persists playback state for resumption after app restart. Stores the current track URL, position,
 * and player settings to enable playback resumption via MediaButtonReceiver (Bluetooth play button,
 * etc.).
 *
 * Settings (repeatMode, shuffleEnabled, playbackSpeed) are auto-persisted when set via properties.
 * Position/URL are persisted via save() on pause or track change.
 */
class PlaybackStateStore(private val player: Player) {
  private val prefs: SharedPreferences =
    player.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /**
   * Persisted playback state for resumption.
   *
   * @param track The track being played (includes url for queue expansion)
   * @param positionMs The playback position in milliseconds (C.TIME_UNSET for default/live edge)
   * @param repeatMode The repeat mode setting
   * @param shuffleEnabled Whether shuffle mode was enabled
   * @param playbackSpeed The playback speed (1.0 = normal)
   */
  data class PersistedState(
    val track: Track,
    val positionMs: Long,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    val playbackSpeed: Float,
  )

  /** Repeat mode setting - auto-persisted when set. */
  var repeatMode: RepeatMode
    get() =
      prefs.getString(KEY_REPEAT_MODE, null)?.let { name ->
        runCatching { RepeatMode.valueOf(name) }.getOrNull()
      } ?: RepeatMode.OFF
    set(value) {
      prefs.edit { putString(KEY_REPEAT_MODE, value.name) }
    }

  /** Shuffle mode setting - auto-persisted when set. */
  var shuffleEnabled: Boolean
    get() = prefs.getBoolean(KEY_SHUFFLE_ENABLED, false)
    set(value) {
      prefs.edit { putBoolean(KEY_SHUFFLE_ENABLED, value) }
    }

  /** Playback speed setting - auto-persisted when set. */
  var playbackSpeed: Float
    get() = prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
    set(value) {
      prefs.edit { putFloat(KEY_PLAYBACK_SPEED, value) }
    }

  private val scope = MainScope()
  private var periodicSaveJob: Job? = null

  /** Starts periodic position saving every 5 seconds. Call when playback starts. */
  fun startPeriodicSave() {
    if (periodicSaveJob != null) return
    launchPeriodicSave()
  }

  /** Resets the periodic save timer on track change to avoid redundant saves shortly after. */
  fun resetPeriodicSave() {
    periodicSaveJob?.cancel()
    periodicSaveJob = null
    launchPeriodicSave()
  }

  private fun launchPeriodicSave() {
    if (!player.playWhenReady) return
    periodicSaveJob =
      scope.launch {
        while (true) {
          delay(PERIODIC_SAVE_INTERVAL_MS)
          if (!player.isCurrentItemLive) {
            savePosition()
          }
        }
      }
  }

  /** Stops periodic position saving and saves final position. Call when playback stops. */
  fun stopPeriodicSave() {
    periodicSaveJob?.cancel()
    periodicSaveJob = null
    if (!player.isCurrentItemLive) {
      savePosition()
    }
  }

  /**
   * Saves the current playback state for later resumption. Call on track change.
   *
   * For live streams, position is stored as C.TIME_UNSET to signal "use default position", which
   * Media3 interprets as the live edge.
   */
  fun save() {
    val track = player.currentTrack ?: return
    val positionMs = if (player.isCurrentItemLive) C.TIME_UNSET else player.position

    prefs.edit {
      putLong(KEY_POSITION_MS, positionMs)
      putString(KEY_TRACK, trackToJson(track))
    }
    Timber.d("Saved playback state: title=${track.title}, positionMs=$positionMs")
  }

  /** Saves just the current position. Call on pause. */
  fun savePosition() {
    val positionMs = if (player.isCurrentItemLive) C.TIME_UNSET else player.position

    prefs.edit { putLong(KEY_POSITION_MS, positionMs) }
    Timber.d("Saved position: positionMs=$positionMs")
  }

  /** Resets saved position to 0. Call when queue ends to start from beginning on resumption. */
  fun savePositionZero() {
    prefs.edit { putLong(KEY_POSITION_MS, 0) }
    Timber.d("Saved position: positionMs=0 (queue ended)")
  }

  private fun trackToJson(track: Track): String =
    JSONObject()
      .apply {
        put("url", track.url)
        put("src", track.src)
        put("title", track.title)
        put("subtitle", track.subtitle)
        put("artist", track.artist)
        put("album", track.album)
        put("artwork", track.artwork)
        put("description", track.description)
        put("genre", track.genre)
        put("duration", track.duration)
        put("style", track.style?.name)
        put("childrenStyle", track.childrenStyle?.name)
        put("favorited", track.favorited)
        put("groupTitle", track.groupTitle)
        put("live", track.live)
      }
      .toString()

  private fun trackFromJson(json: String): Track? =
    runCatching {
        val obj = JSONObject(json)
        Track(
          url = obj.optString("url").takeIf { it.isNotEmpty() },
          src = obj.optString("src").takeIf { it.isNotEmpty() },
          title = obj.getString("title"),
          subtitle = obj.optString("subtitle").takeIf { it.isNotEmpty() },
          artist = obj.optString("artist").takeIf { it.isNotEmpty() },
          album = obj.optString("album").takeIf { it.isNotEmpty() },
          artwork = obj.optString("artwork").takeIf { it.isNotEmpty() },
          artworkSource = null, // Not persisted - will be re-transformed on browse
          description = obj.optString("description").takeIf { it.isNotEmpty() },
          genre = obj.optString("genre").takeIf { it.isNotEmpty() },
          duration =
            if (obj.has("duration") && !obj.isNull("duration")) obj.getDouble("duration") else null,
          style =
            obj
              .optString("style")
              .takeIf { it.isNotEmpty() }
              ?.let { runCatching { TrackStyle.valueOf(it) }.getOrNull() },
          childrenStyle =
            obj
              .optString("childrenStyle")
              .takeIf { it.isNotEmpty() }
              ?.let { runCatching { TrackStyle.valueOf(it) }.getOrNull() },
          favorited =
            if (obj.has("favorited") && !obj.isNull("favorited")) obj.getBoolean("favorited")
            else null,
          groupTitle = obj.optString("groupTitle").takeIf { it.isNotEmpty() },
          live = if (obj.has("live") && !obj.isNull("live")) obj.getBoolean("live") else null,
        )
      }
      .onFailure { e -> Timber.w(e, "Failed to parse persisted track JSON") }
      .getOrNull()

  /**
   * Restores player settings from persisted state and returns the state for queue setup.
   *
   * Applies repeatMode, shuffleMode, and playbackSpeed to the player. The caller is responsible for
   * using the returned url and positionMs to set up the playback queue.
   *
   * @return PersistedState if available, null otherwise
   */
  fun restore(): PersistedState? =
    get()?.also { state ->
      // Apply player settings from persisted state
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
    val trackJson = prefs.getString(KEY_TRACK, null) ?: return null
    val track = trackFromJson(trackJson) ?: return null
    val positionMs = prefs.getLong(KEY_POSITION_MS, 0)

    return PersistedState(track, positionMs, repeatMode, shuffleEnabled, playbackSpeed)
  }

  /** Clears the persisted playback state. */
  fun clear() {
    prefs.edit { clear() }
    Timber.d("Cleared playback state")
  }

  companion object {
    private const val PREFS_NAME = "audio_browser_playback_state_v3"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
    private const val KEY_PLAYBACK_SPEED = "playback_speed"
    private const val KEY_TRACK = "track"
    private const val PERIODIC_SAVE_INTERVAL_MS = 5000L
  }
}
