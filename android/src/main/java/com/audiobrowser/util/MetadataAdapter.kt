package com.audiobrowser.util

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.id3.UrlLinkFrame
import com.margelo.nitro.audiobrowser.ChapterMetadata
import com.margelo.nitro.audiobrowser.TrackMetadata

sealed class MetadataAdapter {
  companion object {
    /**
     * Extracts chapter metadata from ID3 ChapterFrames.
     * Returns a list of ChapterMetadata, one per chapter.
     */
    fun extractChapters(metadata: Metadata): List<ChapterMetadata> {
      val chapters = mutableListOf<ChapterMetadata>()

      for (i in 0 until metadata.length()) {
        val entry = metadata[i]
        if (entry is ChapterFrame) {
          // TODO: Artwork extraction missing. Requires converting ApicFrame binary
          // image data to a URL (base64 data URL or temp file).
          // See also: iOS implementation in ChapterMetadata+AVFoundation.swift
          var title: String? = null
          var url: String? = null

          // Parse sub-frames for chapter metadata
          for (j in 0 until entry.subFrameCount) {
            when (val subFrame = entry.getSubFrame(j)) {
              is TextInformationFrame -> {
                when (subFrame.id.uppercase()) {
                  "TIT2", "TT2" -> title = subFrame.values.firstOrNull()
                }
              }
              is UrlLinkFrame -> {
                url = subFrame.url
              }
            }
          }

          // Convert milliseconds to seconds
          val startTime = entry.startTimeMs / 1_000.0
          val endTime = entry.endTimeMs / 1_000.0

          chapters.add(
            ChapterMetadata(
              startTime = startTime,
              endTime = endTime,
              title = title ?: entry.id,
              url = url,
            )
          )
        }
      }

      return chapters
    }

    fun trackMetadataFromMediaMetadata(metadata: MediaMetadata): TrackMetadata {
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

      return TrackMetadata(
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
