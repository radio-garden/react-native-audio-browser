package com.doublesymmetry.trackplayer.util

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.extractor.metadata.icy.IcyHeaders
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.id3.UrlLinkFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import timber.log.Timber

sealed class MetadataAdapter {
  companion object {
    fun fromMetadata(metadata: Metadata): List<WritableMap> {
      val group = mutableListOf<WritableMap>()

      (0 until metadata.length()).forEach { i ->
        group.add(
          Arguments.createMap().apply {
            val rawEntries = mutableListOf<WritableMap>()

            when (val entry = metadata[i]) {
              is ChapterFrame -> {
                Timber.d("ChapterFrame: ${entry.id}")
              }
              is TextInformationFrame -> {
                val rawEntry = Arguments.createMap()

                when (entry.id.uppercase()) {
                  "TIT2",
                  "TT2" -> {
                    putString("title", entry.values[0])
                    rawEntry.putString("commonKey", "title")
                  }
                  "TALB",
                  "TOAL",
                  "TAL" -> {
                    putString("albumName", entry.values[0])
                    rawEntry.putString("commonKey", "albumName")
                  }
                  "TOPE",
                  "TPE1",
                  "TP1" -> {
                    putString("artist", entry.values[0])
                    rawEntry.putString("commonKey", "artist")
                  }
                  "TDRC",
                  "TOR" -> {
                    putString("creationDate", entry.values[0])
                    rawEntry.putString("commonKey", "creationDate")
                  }
                  "TCON",
                  "TCO" -> {
                    putString("genre", entry.values[0])
                    rawEntry.putString("commonKey", "genre")
                  }
                }

                rawEntry.putString("key", entry.id.uppercase())
                rawEntry.putString("keySpace", "org.id3")
                rawEntry.putString("value", entry.values[0])
                rawEntries.add(rawEntry)
              }

              is UrlLinkFrame -> {
                rawEntries.add(
                  Arguments.createMap().apply {
                    putString("value", entry.url)
                    putString("key", entry.id.uppercase())
                    putString("keySpace", "org.id3")
                  }
                )
              }

              is IcyHeaders -> {
                putString("title", entry.name)
                putString("genre", entry.genre)

                rawEntries.add(
                  Arguments.createMap().apply {
                    putString("value", entry.name)
                    putString("commonKey", "title")
                    putString("key", "StreamTitle")
                    putString("keySpace", "icy")
                  }
                )

                rawEntries.add(
                  Arguments.createMap().apply {
                    putString("value", entry.url)
                    putString("key", "StreamURL")
                    putString("keySpace", "icy")
                  }
                )

                rawEntries.add(
                  Arguments.createMap().apply {
                    putString("value", entry.genre)
                    putString("commonKey", "genre")
                    putString("key", "StreamGenre")
                    putString("keySpace", "icy")
                  }
                )
              }

              is IcyInfo -> {
                putString("title", entry.title)

                rawEntries.add(
                  Arguments.createMap().apply {
                    putString("value", entry.url)
                    putString("key", "StreamURL")
                    putString("keySpace", "icy")
                  }
                )

                rawEntries.add(
                  Arguments.createMap().apply {
                    putString("value", entry.title)
                    putString("commonKey", "title")
                    putString("key", "StreamTitle")
                    putString("keySpace", "icy")
                  }
                )
              }

              is VorbisComment -> {
                val rawEntry = Arguments.createMap()

                when (entry.key) {
                  "TITLE" -> {
                    putString("title", entry.value)
                    rawEntry.putString("commonKey", "title")
                  }
                  "ARTIST" -> {
                    putString("artist", entry.value)
                    rawEntry.putString("commonKey", "artist")
                  }
                  "ALBUM" -> {
                    putString("albumName", entry.value)
                    rawEntry.putString("commonKey", "albumName")
                  }
                  "DATE" -> {
                    putString("creationDate", entry.value)
                    rawEntry.putString("commonKey", "creationDate")
                  }
                  "GENRE" -> {
                    putString("genre", entry.value)
                    rawEntry.putString("commonKey", "genre")
                  }
                  "URL" -> {
                    putString("url", entry.value)
                  }
                }

                rawEntry.putString("key", entry.key)
                rawEntry.putString("keySpace", "org.vorbis")
                rawEntry.putString("value", entry.value)
                rawEntries.add(rawEntry)
              }

              is MdtaMetadataEntry -> {
                val rawEntry = Arguments.createMap()
                when (entry.key) {
                  "com.apple.quicktime.title" -> {
                    putString("title", entry.value.toString())
                    rawEntry.putString("commonKey", "title")
                  }
                  "com.apple.quicktime.artist" -> {
                    putString("artist", entry.value.toString())
                    rawEntry.putString("commonKey", "artist")
                  }
                  "com.apple.quicktime.album" -> {
                    putString("albumName", entry.value.toString())
                    rawEntry.putString("commonKey", "albumName")
                  }
                  "com.apple.quicktime.creationdate" -> {
                    putString("creationDate", entry.value.toString())
                    rawEntry.putString("commonKey", "creationDate")
                  }
                  "com.apple.quicktime.genre" -> {
                    putString("genre", entry.value.toString())
                    rawEntry.putString("commonKey", "genre")
                  }
                }

                rawEntry.putString("key", entry.key.substringAfterLast("."))
                rawEntry.putString("keySpace", "com.apple.quicktime")
                rawEntry.putString("value", entry.value.toString())
                rawEntries.add(rawEntry)
              }
            }

            val rawArray = Arguments.createArray()
            rawEntries.forEach { rawArray.pushMap(it) }
            putArray("raw", rawArray)
          }
        )
      }

      return group
    }

    fun mapFromMediaMetadata(metadata: MediaMetadata): ReadableMap {
      return Arguments.createMap().apply {
        metadata.title?.let { putString("title", it.toString()) }
        metadata.artist?.let { putString("artist", it.toString()) }
        metadata.albumTitle?.let { putString("albumName", it.toString()) }
        metadata.subtitle?.let { putString("subtitle", it.toString()) }
        metadata.description?.let { putString("description", it.toString()) }
        metadata.artworkUri?.let { putString("artworkUri", it.toString()) }
        metadata.trackNumber?.let { putInt("trackNumber", it) }
        metadata.composer?.let { putString("composer", it.toString()) }
        metadata.conductor?.let { putString("conductor", it.toString()) }
        metadata.genre?.let { putString("genre", it.toString()) }
        metadata.compilation?.let { putString("compilation", it.toString()) }
        metadata.station?.let { putString("station", it.toString()) }
        metadata.mediaType?.let { putInt("mediaType", it) }

        // This is how SwiftAudioEx outputs it in the metadata dictionary
        (metadata.recordingDay to metadata.recordingMonth).let { (day, month) ->
          // if both are not null, combine them into a single string
          if (day != null && month != null) {
            putString(
              "creationDate",
              "${String.format("%02d", day)}${String.format("%02d", month)}",
            )
          } else if (day != null) {
            putString("creationDate", String.format("%02d", day))
          } else if (month != null) {
            putString("creationDate", String.format("%02d", month))
          }
        }
        metadata.recordingYear?.let { putString("creationYear", it.toString()) }
      }
    }
  }
}
