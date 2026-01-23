package com.audiobrowser.model

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.margelo.nitro.audiobrowser.TimedMetadata

data class PlaybackMetadata(
  var title: String? = null,
  var artist: String? = null,
  var album: String? = null,
  var date: String? = null,
  var genre: String? = null,
) {
  companion object {
    /**
     * Creates PlaybackMetadata from raw Metadata entries.
     * Handles ICY streams specially, uses Media3's unified approach for everything else.
     */
    fun from(metadata: Metadata): PlaybackMetadata? {
      // Try ICY first (streaming-specific)
      fromIcy(metadata)?.let { return it }

      // Use Media3's unified approach for ID3, Vorbis, QuickTime, etc.
      val builder = MediaMetadata.Builder()
      for (i in 0 until metadata.length()) {
        metadata[i].populateMediaMetadata(builder)
      }
      return fromMediaMetadata(builder.build())
    }

    /** Shoutcast / Icecast metadata (ICY protocol) https://cast.readme.io/docs/icy */
    private fun fromIcy(metadata: Metadata): PlaybackMetadata? {
      for (i in 0 until metadata.length()) {
        when (val entry = metadata[i]) {
          is IcyInfo -> {
            if (entry.title != null) {
              return PlaybackMetadata(title = entry.title)
            }
          }
        }
      }
      return null
    }

    /** Converts Media3's unified MediaMetadata to PlaybackMetadata. */
    private fun fromMediaMetadata(mediaMetadata: MediaMetadata): PlaybackMetadata? {
      val title = mediaMetadata.title?.toString()
      val artist = mediaMetadata.artist?.toString()
      val album = mediaMetadata.albumTitle?.toString()
      val genre = mediaMetadata.genre?.toString()

      // Use recordingYear first (more specific), fall back to releaseYear
      val date = (mediaMetadata.recordingYear ?: mediaMetadata.releaseYear)?.toString()

      // Return null if no meaningful data
      if (title == null && artist == null && album == null && genre == null && date == null) {
        return null
      }

      return PlaybackMetadata(
        title = title,
        artist = artist,
        album = album,
        date = date,
        genre = genre,
      )
    }
  }

  fun toNitro(): TimedMetadata {
    return TimedMetadata(
      title = title,
      artist = artist,
      album = album,
      date = date,
      genre = genre,
    )
  }
}
