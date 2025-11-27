package com.audiobrowser.util

import androidx.core.net.toUri
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.margelo.nitro.audiobrowser.Track

object TrackFactory {
  fun fromMedia3(mediaItem: MediaItem): Track {
    return mediaItem.localConfiguration!!.tag as Track
  }

  fun toMedia3(tracks: Array<Track>): List<MediaItem> {
    return tracks.map { toMedia3(it) }
  }

  fun toMedia3(track: Track): MediaItem {
    val extras = MediaExtrasBuilder.build(track)

    val mediaMetadata =
      MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist)
        .setAlbumTitle(track.album)
        .setDescription(track.description)
        .setGenre(track.genre)
        .setArtworkUri(track.artwork?.toUri())
        .setIsBrowsable(track.src == null)
        .setIsPlayable(track.src != null)
        .setExtras(extras)
        .apply { track.favorited?.let { setUserRating(HeartRating(it)) } }
        .build()

    val mediaId =
      track.url
        ?: track.src
        ?: throw IllegalArgumentException(
          "Track must have either url or src defined. Track: title='${track.title}', artist='${track.artist}'"
        )

    return MediaItem.Builder()
      .setMediaId(mediaId)
      .setUri(track.src)
      .setMediaMetadata(mediaMetadata)
      .setTag(track)
      .build()
  }
}
