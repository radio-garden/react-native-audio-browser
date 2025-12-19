package com.audiobrowser.util

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import coil3.ImageLoader
import com.margelo.nitro.audiobrowser.Track

object TrackFactory {
  fun fromMedia3(mediaItem: MediaItem): Track {
    return mediaItem.localConfiguration!!.tag as Track
  }

  fun toMedia3(tracks: Array<Track>): List<MediaItem> {
    return tracks.map { toMedia3(it) }
  }

  /**
   * Converts a Track to a Media3 MediaItem.
   *
   * Note: This synchronous version uses setArtworkUri() which doesn't support SVG.
   * For Android Auto browse items with SVG artwork, use [toMedia3WithSvgSupport] instead.
   */
  fun toMedia3(track: Track): MediaItem {
    val extras = MediaExtrasBuilder.build(track)

    // Use transformed artworkSource.uri if available, otherwise fall back to original artwork
    val artworkUri = track.artworkSource?.uri ?: track.artwork

    val mediaMetadata =
      MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist)
        .setAlbumTitle(track.album)
        .setDescription(track.description)
        .setGenre(track.genre)
        .setArtworkUri(artworkUri?.toUri())
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

  /**
   * Converts a Track to a Media3 MediaItem with SVG artwork support.
   *
   * For SVG artwork URLs, this pre-renders the image to PNG and embeds it
   * using setArtworkData(). This is necessary for Android Auto which doesn't
   * support loading SVG images from URLs.
   *
   * @param track The track to convert
   * @param context Android context for image loading
   * @param imageLoader Coil ImageLoader for SVG rendering
   * @return MediaItem with embedded artwork for SVGs, or URI-based artwork for other formats
   */
  suspend fun toMedia3WithSvgSupport(
    track: Track,
    context: Context,
    imageLoader: ImageLoader,
  ): MediaItem {
    val extras = MediaExtrasBuilder.build(track)

    // Use transformed artworkSource.uri if available, otherwise fall back to original artwork
    val artworkUrl = track.artworkSource?.uri ?: track.artwork

    // Build metadata with SVG support
    val metadataBuilder = MediaMetadata.Builder()
      .setTitle(track.title)
      .setArtist(track.artist)
      .setAlbumTitle(track.album)
      .setDescription(track.description)
      .setGenre(track.genre)
      .setIsBrowsable(track.src == null)
      .setIsPlayable(track.src != null)
      .setExtras(extras)
      .apply { track.favorited?.let { setUserRating(HeartRating(it)) } }

    // Apply artwork with SVG pre-rendering if needed
    SvgArtworkRenderer.applyArtwork(metadataBuilder, artworkUrl, context, imageLoader)

    val mediaMetadata = metadataBuilder.build()

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

  /**
   * Converts multiple Tracks to Media3 MediaItems with SVG artwork support.
   */
  suspend fun toMedia3WithSvgSupport(
    tracks: Array<Track>,
    context: Context,
    imageLoader: ImageLoader,
  ): List<MediaItem> {
    return tracks.map { toMedia3WithSvgSupport(it, context, imageLoader) }
  }
}
