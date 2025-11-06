package com.audiobrowser.util

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.extractor.metadata.icy.IcyHeaders
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.id3.UrlLinkFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.audiobrowser.model.MetadataEntry
import com.audiobrowser.model.TimedMetadata
import com.margelo.nitro.audiobrowser.AudioMetadata
import timber.log.Timber

sealed class MetadataAdapter {
  companion object {
    fun toTimedMetadata(metadata: Metadata): TimedMetadata {
      val entries = mutableListOf<MetadataEntry>()

      (0 until metadata.length()).forEach { i ->
        var title: String? = null
        var artist: String? = null
        var albumTitle: String? = null
        var genre: String? = null
        var creationDate: String? = null
        var url: String? = null

        when (val entry = metadata[i]) {
          is ChapterFrame -> {
            Timber.d("ChapterFrame: ${entry.id}")
          }
          is TextInformationFrame -> {
            when (entry.id.uppercase()) {
              "TIT2",
              "TT2" -> {
                title = entry.values[0]
              }
              "TALB",
              "TOAL",
              "TAL" -> {
                albumTitle = entry.values[0]
              }
              "TOPE",
              "TPE1",
              "TP1" -> {
                artist = entry.values[0]
              }
              "TDRC",
              "TOR" -> {
                creationDate = entry.values[0]
              }
              "TCON",
              "TCO" -> {
                genre = entry.values[0]
              }
              else -> {}
            }
          }
          is UrlLinkFrame -> {
            url = entry.url
          }
          is IcyHeaders -> {
            title = entry.name
            genre = entry.genre
          }
          is IcyInfo -> {
            title = entry.title
            url = entry.url
          }
          is VorbisComment -> {
            when (entry.key) {
              "TITLE" -> {
                title = entry.value
              }
              "ARTIST" -> {
                artist = entry.value
              }
              "ALBUM" -> {
                albumTitle = entry.value
              }
              "DATE" -> {
                creationDate = entry.value
              }
              "GENRE" -> {
                genre = entry.value
              }
            }
          }
          is MdtaMetadataEntry -> {
            when (entry.key) {
              "com.apple.quicktime.title" -> {
                title = entry.value.toString()
              }
              "com.apple.quicktime.artist" -> {
                artist = entry.value.toString()
              }
              "com.apple.quicktime.album" -> {
                albumTitle = entry.value.toString()
              }
              "com.apple.quicktime.creationdate" -> {
                creationDate = entry.value.toString()
              }
              "com.apple.quicktime.genre" -> {
                genre = entry.value.toString()
              }
            }
          }
        }

        entries.add(
          MetadataEntry(
            title = title,
            artist = artist,
            albumTitle = albumTitle,
            genre = genre,
            creationDate = creationDate,
            url = url,
          )
        )
      }

      return TimedMetadata(entries)
    }

    fun audioMetadataFromMediaMetadata(metadata: MediaMetadata): AudioMetadata {
      // Handle creation date from recording day and month
      val creationDate =
        (metadata.recordingDay to metadata.recordingMonth).let { (day, month) ->
          // if both are not null, combine them into a single string
          if (day != null && month != null) {
            "${String.format("%02d", day)}${String.format("%02d", month)}"
          } else if (day != null) {
            String.format("%02d", day)
          } else if (month != null) {
            String.format("%02d", month)
          } else {
            null
          }
        }

      return AudioMetadata(
        title = metadata.title as String?,
        artist = metadata.artist as String?,
        albumTitle = metadata.albumTitle as String?,
        subtitle = metadata.subtitle as String?,
        description = metadata.description as String?,
        artworkUri = metadata.artworkUri?.toString(),
        trackNumber = metadata.trackNumber?.toString(),
        composer = metadata.composer as String?,
        conductor = metadata.conductor as String?,
        genre = metadata.genre as String?,
        compilation = metadata.compilation as String?,
        station = metadata.station as String?,
        mediaType = metadata.mediaType?.toString(),
        creationDate = creationDate,
        creationYear = metadata.recordingYear?.toString(),
        url = null,
      )
    }
  }
}
