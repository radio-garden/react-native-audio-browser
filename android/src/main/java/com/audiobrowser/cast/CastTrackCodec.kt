package com.audiobrowser.cast

import com.margelo.nitro.audiobrowser.Track
import org.json.JSONObject

/**
 * The single Track ↔ JSON codec for the Cast `customData` payload — the app's stable Track identity
 * that travels with each `MediaQueueItem` so a relaunch-while-casting (or a reactive re-sign) can
 * re-resolve full Tracks (see ADR 0003).
 *
 * Lives in the **main** sourceset (not `cast`) on purpose: it touches only the generated [Track] and
 * `org.json`, no Cast SDK, so it compiles harmlessly in the default build AND is reachable from
 * `src/test`. Mirrors how the codebase isolates pure logic (`PlaybackStateMachine`,
 * `StuckRecoveryPolicy`, `CapabilityControls`). Replaces three previously hand-rolled copies.
 *
 * Note: this is a deliberately partial, identity-only schema — the fields needed to re-resolve a
 * Track, not a full Track serializer. Distinct from `PlaybackStateStore`'s `"track"` codec (a
 * different schema for resumption).
 */
object CastTrackCodec {
  /** customData key under which the serialized Track identity is stored on a MediaQueueItem. */
  const val KEY_TRACK = "audiobrowserTrack"

  fun toJson(track: Track): JSONObject =
    JSONObject().apply {
      track.id?.let { put("id", it) }
      track.url?.let { put("url", it) }
      track.src?.let { put("src", it) }
      put("title", track.title)
      track.subtitle?.let { put("subtitle", it) }
      track.artist?.let { put("artist", it) }
      track.album?.let { put("album", it) }
      track.artwork?.let { put("artwork", it) }
      track.live?.let { put("live", it) }
    }

  /**
   * Extracts and parses the keyed Track identity from a MediaQueueItem's `customData` envelope (the
   * `{ "audiobrowserTrack": {...} }` shape written on the way out). Returns null when absent or
   * unparseable. Keeps the keyed-extraction in one place for both the converter and the re-sign.
   */
  fun fromCustomData(customData: JSONObject?): Track? {
    val keyed = customData?.optJSONObject(KEY_TRACK) ?: return null
    return runCatching { fromJson(keyed) }.getOrNull()
  }

  fun fromJson(json: JSONObject): Track =
    blankTrack().copy(
      id = json.optString("id").ifBlank { null },
      url = json.optString("url").ifBlank { null },
      src = json.optString("src").ifBlank { null },
      title = json.optString("title", ""),
      subtitle = json.optString("subtitle").ifBlank { null },
      artist = json.optString("artist").ifBlank { null },
      album = json.optString("album").ifBlank { null },
      artwork = json.optString("artwork").ifBlank { null },
      live = if (json.has("live")) json.optBoolean("live") else null,
    )

  /** A Track with every field null/default — `copy(...)` fills the few the Cast schema carries. */
  fun blankTrack(): Track =
    Track(
      id = null,
      url = null,
      src = null,
      artwork = null,
      artworkSource = null,
      request = null,
      artworkCarPlayTinted = null,
      title = "",
      subtitle = null,
      artist = null,
      albumUrl = null,
      album = null,
      description = null,
      genre = null,
      duration = null,
      style = null,
      childrenStyle = null,
      favorited = null,
      groupTitle = null,
      live = null,
      imageRow = null,
    )
}
