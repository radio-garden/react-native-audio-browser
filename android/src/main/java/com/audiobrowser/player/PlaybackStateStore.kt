package com.audiobrowser.player

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.common.C
import com.audiobrowser.util.EMPTY_TRACK_STYLE
import com.audiobrowser.util.artworkOf
import com.audiobrowser.util.toArtworkRendering
import com.audiobrowser.util.toCardImage
import com.audiobrowser.util.toImageShape
import com.audiobrowser.util.toStyleDisplay
import com.audiobrowser.util.toWireString
import com.audiobrowser.util.url
import com.margelo.nitro.audiobrowser.PlaybackState
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
 * Persists the current track, position and player settings so a media button (a Bluetooth play
 * press) can resume playback after process death. The settings properties write through on set;
 * track and position persist only through the save calls.
 */
class PlaybackStateStore(private val player: Player) {
  private val prefs: SharedPreferences =
    player.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /** [positionMs] is [C.TIME_UNSET] for a live track: Media3 starts it at the live edge. */
  data class PersistedState(
    val track: Track,
    val positionMs: Long,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    val playbackSpeed: Float,
  )

  var repeatMode: RepeatMode
    get() =
      prefs.getString(KEY_REPEAT_MODE, null)?.let { name ->
        runCatching { RepeatMode.valueOf(name) }.getOrNull()
      } ?: RepeatMode.OFF
    set(value) {
      prefs.edit { putString(KEY_REPEAT_MODE, value.name) }
    }

  var shuffleEnabled: Boolean
    get() = prefs.getBoolean(KEY_SHUFFLE_ENABLED, false)
    set(value) {
      prefs.edit { putBoolean(KEY_SHUFFLE_ENABLED, value) }
    }

  var playbackSpeed: Float
    get() = prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
    set(value) {
      prefs.edit { putFloat(KEY_PLAYBACK_SPEED, value) }
    }

  private val scope = MainScope()
  private var periodicSaveJob: Job? = null

  /**
   * Calls [save] every [PERIODIC_SAVE_INTERVAL_MS] while playing. A no-op when already running or
   * when the player is not set to play.
   */
  fun startPeriodicSave() {
    if (periodicSaveJob != null) return
    launchPeriodicSave()
  }

  /** Restarts the interval, so a [save] on track change is not followed by an immediate repeat. */
  fun resetPeriodicSave() {
    cancelPeriodicSave()
    launchPeriodicSave()
  }

  private fun launchPeriodicSave() {
    if (!player.playWhenReady) return
    periodicSaveJob =
      scope.launch {
        while (true) {
          delay(PERIODIC_SAVE_INTERVAL_MS)
          save()
        }
      }
  }

  private fun cancelPeriodicSave() {
    periodicSaveJob?.cancel()
    periodicSaveJob = null
  }

  /** Stops the periodic save and calls [save] once more. */
  fun stopPeriodicSave() {
    cancelPeriodicSave()
    // After a natural end, setPlaybackState(ENDED) has saved position 0; a save here, from the
    // dropped intent or a teardown, would make resumption start at the end of the track.
    if (player.playbackState != PlaybackState.ENDED) {
      save()
    }
  }

  /** Saves the current track and its position; does nothing without a current track. */
  fun save() {
    val track = player.currentTrack ?: return
    // A live item reports 0 until it has loaded, so 0 is saved unset too.
    val positionMs =
      player.position.takeUnless { player.isCurrentItemLive || it == 0L } ?: C.TIME_UNSET

    prefs.edit {
      putLong(KEY_POSITION_MS, positionMs)
      putString(KEY_TRACK, trackToJson(track))
    }
    Timber.d("Saved playback state: title=${track.title}, positionMs=$positionMs")
  }

  fun savePositionZero() {
    prefs.edit { putLong(KEY_POSITION_MS, 0) }
    Timber.d("Saved position: positionMs=0 (queue ended)")
  }

  /**
   * [get], then applies its repeat mode, shuffle and speed to the player, which emits option-change
   * events. Main thread only. The caller sets up the queue from the returned track and position.
   */
  fun restore(): PersistedState? =
    get()?.also { state ->
      player.repeatMode = state.repeatMode
      player.shuffleMode = state.shuffleEnabled
      // ExoPlayer throws on a non-positive speed; a corrupted prefs file holding one would fail
      // every resume for good, since the value stays persisted.
      if (state.playbackSpeed > 0f) player.playbackSpeed = state.playbackSpeed

      Timber.d(
        "Restored player settings: repeatMode=${state.repeatMode}, shuffle=${state.shuffleEnabled}, speed=${state.playbackSpeed}"
      )
    }

  /** The persisted state, read without touching the player; null if none or it won't parse. */
  fun get(): PersistedState? {
    val trackJson = prefs.getString(KEY_TRACK, null) ?: return null
    val track = trackFromJson(trackJson) ?: return null
    val positionMs = prefs.getLong(KEY_POSITION_MS, 0)

    return PersistedState(track, positionMs, repeatMode, shuffleEnabled, playbackSpeed)
  }

  fun clear() {
    prefs.edit { clear() }
    Timber.d("Cleared playback state")
  }

  companion object {
    internal fun trackToJson(track: Track): String =
      JSONObject()
        .apply {
          put("id", track.id)
          put("path", track.path)
          put("src", track.src)
          put("title", track.title)
          put("subtitle", track.subtitle)
          put("artist", track.artist)
          put("albumPath", track.albumPath)
          put("album", track.album)
          put("artwork", track.artwork?.url)
          put("description", track.description)
          put("genre", track.genre)
          put("duration", track.duration)
          // Persisted in its wire shape so a restored queue keeps its styling (ADR 0011).
          put(
            "style",
            track.style?.let { style ->
              JSONObject().apply {
                put("display", style.display?.toWireString())
                put("artworkRendering", style.artworkRendering?.toWireString())
                put("imageShape", style.imageShape?.toWireString())
                put("accessorySymbol", style.accessorySymbol)
                put("cardTint", style.cardTint)
                put("cardImage", style.cardImage?.toWireString())
              }
            },
          )
          put("disabled", track.disabled)
          put("favorited", track.favorited)
          put("live", track.live)
          put("icyCharset", track.icyCharset)
        }
        .toString()

    internal fun trackFromJson(json: String): Track? =
      runCatching {
          val obj = JSONObject(json)
          Track(
            id = obj.optString("id").takeIf { it.isNotEmpty() },
            path = obj.optString("path").takeIf { it.isNotEmpty() },
            src = obj.optString("src").takeIf { it.isNotEmpty() },
            title = obj.getString("title"),
            subtitle = obj.optString("subtitle").takeIf { it.isNotEmpty() },
            artist = obj.optString("artist").takeIf { it.isNotEmpty() },
            albumPath = obj.optString("albumPath").takeIf { it.isNotEmpty() },
            album = obj.optString("album").takeIf { it.isNotEmpty() },
            artwork = artworkOf(obj.optString("artwork").takeIf { it.isNotEmpty() }),
            artworkSource = null, // Not persisted: re-derived on the next browse
            description = obj.optString("description").takeIf { it.isNotEmpty() },
            genre = obj.optString("genre").takeIf { it.isNotEmpty() },
            duration =
              if (obj.has("duration") && !obj.isNull("duration")) obj.getDouble("duration")
              else null,
            // optJSONObject, not getJSONObject: a snapshot from before the block model holds
            // `style` as a string, and its track restores without a style (ADR 0011).
            style =
              obj
                .optJSONObject("style")
                ?.let { style ->
                  TrackStyle(
                    display = style.optString("display").toStyleDisplay(),
                    artworkRendering = style.optString("artworkRendering").toArtworkRendering(),
                    imageShape = style.optString("imageShape").toImageShape(),
                    accessorySymbol = style.optString("accessorySymbol").takeIf { it.isNotEmpty() },
                    cardTint = style.optString("cardTint").takeIf { it.isNotEmpty() },
                    cardImage = style.optString("cardImage").toCardImage(),
                  )
                }
                // An empty block means no style, as in the browse decoders on every platform.
                ?.takeIf { it != EMPTY_TRACK_STYLE },
            disabled =
              if (obj.has("disabled") && !obj.isNull("disabled")) obj.getBoolean("disabled")
              else null,
            favorited =
              if (obj.has("favorited") && !obj.isNull("favorited")) obj.getBoolean("favorited")
              else null,
            live = if (obj.has("live") && !obj.isNull("live")) obj.getBoolean("live") else null,
            icyCharset = obj.optString("icyCharset").takeIf { it.isNotEmpty() },
            // Not persisted: resumption re-browses the contextual path, which supplies requests.
            request = null,
          )
        }
        .onFailure { e -> Timber.w(e, "Failed to parse persisted track JSON") }
        .getOrNull()

    private const val PREFS_NAME = "audio_browser_playback_state_v3"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
    private const val KEY_PLAYBACK_SPEED = "playback_speed"
    private const val KEY_TRACK = "track"
    internal const val PERIODIC_SAVE_INTERVAL_MS = 5000L
  }
}
