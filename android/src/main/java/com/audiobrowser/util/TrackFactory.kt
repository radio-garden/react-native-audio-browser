package com.audiobrowser.util

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media.utils.MediaConstants
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.margelo.nitro.audiobrowser.Track


object TrackFactory {

  fun fromMedia3(mediaItems: List<MediaItem>): Array<Track> {
    return mediaItems.map { fromMedia3(it) }.toTypedArray()
  }

  fun fromMedia3(mediaItem: MediaItem): Track {
    return mediaItem.localConfiguration!!.tag as Track
  }

  fun toMedia3(tracks: Array<Track>): List<MediaItem> {
    return tracks.map { toMedia3(it) }
  }

  fun toMedia3(track: Track): MediaItem {
    // Tracks are browsable when they don't have an src:
    val browsable = track.src == null

    // Tracks are playable when they have an src or when they are specifically marked as playable:
    val playable = !browsable || track.playable == null || track.playable

    val mediaMetadata =
      MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist)
        .setAlbumTitle(track.album)
        .setDescription(track.description)
        .setGenre(track.genre)
        .setArtworkUri(track.artwork?.toUri())
        .setIsBrowsable(browsable)
        .setIsPlayable(playable)
        .build()

    val mediaId = track.url ?: track.src
      ?: throw IllegalArgumentException("Track must have either url or src defined. Track: title='${track.title}', artist='${track.artist}'")


    val extras = Bundle()

    //  private val playableExtras = bundleOf(
    //   MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM
    //   MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS
    //   MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE
    // )



    return MediaItem.Builder()
      .setMediaId(mediaId)
      .setUri(track.src)
      .setMediaMetadata(mediaMetadata)
      .setTag(track)
      .build()
  }
}
