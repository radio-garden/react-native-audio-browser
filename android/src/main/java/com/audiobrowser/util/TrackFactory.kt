package com.audiobrowser.util

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.margelo.nitro.audiobrowser.Track

/**
 * Extension function to check if a track is browsable.
 * A track is browsable if it has a url.
 */
fun Track.isBrowsable(): Boolean = url != null

/**
 * Extension function to check if a track is playable.
 * A track is playable if:
 * - It's not browsable (no url), OR
 * - It has the playable flag set to true, OR
 * - It has a src (media source)
 */
fun Track.isPlayable(): Boolean = !isBrowsable() || playable == true || src != null

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
    val mediaMetadata =
      MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist)
        .setAlbumTitle(track.album)
        .setDescription(track.description)
        .setGenre(track.genre)
        .setArtworkUri(track.artwork?.toUri())
        .setIsBrowsable(track.isBrowsable())
        .setIsPlayable(track.isPlayable())
        .build()

    val mediaId =
      track.url
        ?: track.src
        ?: throw IllegalArgumentException(
          "Track must have either url or src defined. Track: title='${track.title}', artist='${track.artist}'"
        )

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
