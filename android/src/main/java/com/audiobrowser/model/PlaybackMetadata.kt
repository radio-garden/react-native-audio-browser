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
     * The ICY title when [metadata] holds one, otherwise what media3 maps from the other entries
     * (ID3, Vorbis, QuickTime…); null when neither yields a field. [icyCharset] re-decodes ICY
     * titles media3 decoded as Latin-1 ([IcyTitle.redecode]); it never touches other metadata.
     */
    fun from(metadata: Metadata, icyCharset: String? = null): PlaybackMetadata? {
      fromIcy(metadata, icyCharset)?.let {
        return it
      }

      val builder = MediaMetadata.Builder()
      for (i in 0 until metadata.length()) {
        metadata[i].populateMediaMetadata(builder)
      }
      return fromMediaMetadata(builder.build())
    }

    // ICY (Shoutcast / Icecast): https://cast.readme.io/docs/icy
    private fun fromIcy(metadata: Metadata, icyCharset: String?): PlaybackMetadata? {
      for (i in 0 until metadata.length()) {
        when (val entry = metadata[i]) {
          is IcyInfo -> {
            val title = entry.title
            if (title != null) {
              return PlaybackMetadata(
                title = icyCharset?.let { IcyTitle.redecode(title, it) } ?: title
              )
            }
          }
        }
      }
      return null
    }

    private fun fromMediaMetadata(mediaMetadata: MediaMetadata): PlaybackMetadata? {
      val title = mediaMetadata.title?.toString()
      val artist = mediaMetadata.artist?.toString()
      val album = mediaMetadata.albumTitle?.toString()
      val genre = mediaMetadata.genre?.toString()

      val date = (mediaMetadata.recordingYear ?: mediaMetadata.releaseYear)?.toString()

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
    return TimedMetadata(title = title, artist = artist, album = album, date = date, genre = genre)
  }
}
