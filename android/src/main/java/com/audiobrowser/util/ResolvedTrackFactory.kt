package com.audiobrowser.util

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.margelo.nitro.audiobrowser.ResolvedTrack

object ResolvedTrackFactory {

  fun toMedia3(resolvedTrack: ResolvedTrack): MediaItem {
    // ResolvedTracks are always browsable (they have a url)
    val browsable = true

    // ResolvedTracks are playable when they have an src or when they are specifically marked as
    // playable:
    val playable = resolvedTrack.src != null || resolvedTrack.playable == true

    val mediaMetadata =
      MediaMetadata.Builder()
        .setTitle(resolvedTrack.title)
        .setArtist(resolvedTrack.artist)
        .setAlbumTitle(resolvedTrack.album)
        .setDescription(resolvedTrack.description)
        .setGenre(resolvedTrack.genre)
        .setArtworkUri(resolvedTrack.artwork?.toUri())
        .setIsBrowsable(browsable)
        .setIsPlayable(playable)
        .build()

    return MediaItem.Builder()
      .setMediaId(resolvedTrack.url)
      .setUri(resolvedTrack.src)
      .setMediaMetadata(mediaMetadata)
      .setTag(resolvedTrack)
      .build()
  }
}
