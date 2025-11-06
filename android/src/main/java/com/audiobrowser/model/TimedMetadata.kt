package com.audiobrowser.model

import com.margelo.nitro.audiobrowser.AudioMetadata
import com.margelo.nitro.audiobrowser.AudioMetadataReceivedEvent

/** Represents a single metadata entry with raw and parsed information. */
data class MetadataEntry(
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
  val url: String? = null,
)

/** Container for timed metadata events. */
data class TimedMetadata(val entries: List<MetadataEntry>) {
  fun toNitro(): AudioMetadataReceivedEvent {
    val audioMetadataList =
      entries
        .map { entry ->
          AudioMetadata(
            title = entry.title,
            artist = entry.artist,
            albumTitle = entry.albumTitle,
            subtitle = entry.subtitle,
            description = entry.description,
            artworkUri = entry.artworkUri,
            trackNumber = entry.trackNumber,
            composer = entry.composer,
            conductor = entry.conductor,
            genre = entry.genre,
            compilation = entry.compilation,
            station = entry.station,
            mediaType = entry.mediaType,
            creationDate = entry.creationDate,
            creationYear = entry.creationYear,
            url = entry.url,
          )
        }
        .toTypedArray()

    return AudioMetadataReceivedEvent(metadata = audioMetadataList)
  }
}
