package com.doublesymmetry.trackplayer.event

import com.doublesymmetry.trackplayer.model.Track
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/** Event data for when the active track changes. */
data class PlaybackActiveTrackChangedEvent(
  /** The index of previously active track. */
  val lastIndex: Int? = null,
  /** The previously active track or null when there wasn't a previously active track. */
  val lastTrack: Track? = null,
  /** The position of the previously active track in seconds. */
  val lastPosition: Double,
  /** The newly active track index or null if there is no longer an active track. */
  val index: Int? = null,
  /** The newly active track or null if there is no longer an active track. */
  val track: Track? = null,
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply {
      putDouble("lastPosition", lastPosition)
      lastIndex?.let { putInt("lastIndex", it) }
      lastTrack?.let { putMap("lastTrack", it.toBridge()) }
      index?.let { putInt("index", it) }
      track?.let { putMap("track", it.toBridge()) }
    }
  }
}
