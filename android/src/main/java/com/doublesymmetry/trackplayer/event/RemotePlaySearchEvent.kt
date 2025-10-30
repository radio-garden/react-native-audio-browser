package com.doublesymmetry.trackplayer.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/** Event data for remote play search command. */
data class RemotePlaySearchEvent(
  /** The search query. */
  val query: String,
  /** The search focus. */
  val focus: String? = null,
  /** The title to search for. */
  val title: String? = null,
  /** The artist to search for. */
  val artist: String? = null,
  /** The album to search for. */
  val album: String? = null,
  /** The date to search for. */
  val date: String? = null,
  /** The playlist to search for. */
  val playlist: String? = null,
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply {
      putString("query", query)
      focus?.let { putString("focus", it) }
      title?.let { putString("title", it) }
      artist?.let { putString("artist", it) }
      album?.let { putString("album", it) }
      date?.let { putString("date", it) }
      playlist?.let { putString("playlist", it) }
    }
  }
}
