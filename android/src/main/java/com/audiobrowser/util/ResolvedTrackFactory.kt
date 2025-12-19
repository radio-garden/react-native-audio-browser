package com.audiobrowser.util

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.margelo.nitro.audiobrowser.ResolvedTrack

object ResolvedTrackFactory {

  fun toMedia3(resolvedTrack: ResolvedTrack): MediaItem {
    val extras = MediaExtrasBuilder.build(resolvedTrack)

    // Use transformed artworkSource.uri if available, otherwise fall back to original artwork
    val artworkUri = resolvedTrack.artworkSource?.uri ?: resolvedTrack.artwork

    val mediaMetadata =
      MediaMetadata.Builder()
        .setTitle(resolvedTrack.title)
        .setArtist(resolvedTrack.artist)
        .setAlbumTitle(resolvedTrack.album)
        .setDescription(resolvedTrack.description)
        .setGenre(resolvedTrack.genre)
        .setArtworkUri(artworkUri?.toUri())
        .setIsBrowsable(resolvedTrack.src == null)
        .setIsPlayable(resolvedTrack.src != null)
        .setExtras(extras)
        .build()

    return MediaItem.Builder()
      .setMediaId(resolvedTrack.url)
      .setUri(resolvedTrack.src)
      .setMediaMetadata(mediaMetadata)
      .setTag(resolvedTrack)
      .build()
  }
}
