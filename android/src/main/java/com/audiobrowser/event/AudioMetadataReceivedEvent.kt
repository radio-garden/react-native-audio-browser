package com.audiobrowser.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/** Event data for when audio metadata is received. */
data class AudioMetadataReceivedEvent(
  /** The metadata items received. */
  val metadata: List<AudioMetadata>
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply {
      putArray("metadata", Arguments.fromList(metadata.map { it.toBridge() }))
    }
  }
}

/** Represents audio metadata with common fields and raw entries. */
data class AudioMetadata(
  val title: String? = null,
  val artist: String? = null,
  val albumTitle: String? = null,
  val subtitle: String? = null,
  val description: String? = null,
  val artworkUri: String? = null,
  val trackNumber: String? = null,
  val composer: String? = null,
  val conductor: String? = null,
  val genre: String? = null,
  val compilation: String? = null,
  val station: String? = null,
  val mediaType: String? = null,
  val creationDate: String? = null,
  val creationYear: String? = null,
  val raw: List<RawMetadataEntry> = emptyList(),
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply {
      title?.let { putString("title", it) }
      artist?.let { putString("artist", it) }
      albumTitle?.let { putString("albumTitle", it) }
      subtitle?.let { putString("subtitle", it) }
      description?.let { putString("description", it) }
      artworkUri?.let { putString("artworkUri", it) }
      trackNumber?.let { putString("trackNumber", it) }
      composer?.let { putString("composer", it) }
      conductor?.let { putString("conductor", it) }
      genre?.let { putString("genre", it) }
      compilation?.let { putString("compilation", it) }
      station?.let { putString("station", it) }
      mediaType?.let { putString("mediaType", it) }
      creationDate?.let { putString("creationDate", it) }
      creationYear?.let { putString("creationYear", it) }
      if (raw.isNotEmpty()) {
        putArray("raw", Arguments.fromList(raw.map { it.toBridge() }))
      }
    }
  }
}

/** Represents a raw metadata entry. */
data class RawMetadataEntry(
  val commonKey: String? = null,
  val keySpace: String? = null,
  val time: Double? = null,
  val value: Any? = null,
  val key: String,
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply {
      commonKey?.let { putString("commonKey", it) }
      keySpace?.let { putString("keySpace", it) }
      time?.let { putDouble("time", it) }
      putString("key", key)
      // value handling would need type checking
      when (value) {
        is String -> putString("value", value)
        is Number -> putDouble("value", value.toDouble())
        is Boolean -> putBoolean("value", value)
        null -> putNull("value")
      }
    }
  }
}
