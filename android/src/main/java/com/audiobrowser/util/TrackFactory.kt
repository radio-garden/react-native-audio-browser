package com.audiobrowser.util

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.audiobrowser.browser.BrowseArtworkRegistry
import com.audiobrowser.browser.ResolvedArtwork
import com.margelo.nitro.audiobrowser.Track

/**
 * The single Track → Media3 [MediaItem] conversion. Owns the two easy-to-drift fallbacks: the
 * displayed artwork is the transformed `artworkSource.uri` falling back to the raw `artwork` field,
 * and the mediaId is `url` falling back to `src` (a Track must have one of the two — see the `src`
 * vs `url` note in CONTEXT.md). The list line renders from `subtitle` (the now-playing line is
 * re-stamped from `artist` by the now-playing pipeline; see the Now Playing Metadata guide).
 */
object TrackFactory {
  fun fromMedia3(mediaItem: MediaItem): Track {
    return mediaItem.localConfiguration!!.tag as Track
  }

  fun toMedia3(tracks: Array<Track>): List<MediaItem> {
    return tracks.map { toMedia3(it) }
  }

  fun toMedia3(track: Track): MediaItem {
    val metadata = metadataBuilder(track).setArtworkUri(artworkUri(track)?.toUri()).build()
    return buildMediaItem(track, metadata)
  }

  /**
   * Browse-surface conversion. Routes http(s) artwork through the content:// provider (so headers +
   * SVG apply in our process, and no bytes cross the Binder), registering it in [registry].
   * Non-http artwork (android.resource:// tab icons, file://) passes through to setArtworkUri
   * unchanged so vector/category icons survive. Plain toMedia3 (queue/now-playing) is unaffected.
   */
  fun toBrowseMediaItem(
    track: Track,
    registry: BrowseArtworkRegistry,
    authority: String,
  ): MediaItem {
    val rawUrl = artworkUri(track) // artworkSource.uri ?: artwork
    val scheme = rawUrl?.let { Uri.parse(it).scheme?.lowercase() }
    val builder = metadataBuilder(track)
    if (rawUrl != null && (scheme == "http" || scheme == "https")) {
      val isSvg = SvgArtworkRenderer.isSvgUrl(rawUrl) || SvgArtworkRenderer.isSvgUrl(track.artwork?.url)
      val token = ArtworkUris.tokenFor(rawUrl)
      registry.register(token, ResolvedArtwork(rawUrl, track.artworkSource?.headers, isSvg))
      builder.setArtworkUri(ArtworkUris.contentUri(authority, token).toUri())
    } else if (rawUrl != null) {
      builder.setArtworkUri(rawUrl.toUri())
    }
    return buildMediaItem(track, builder.build())
  }

  /** The transformed artworkSource wins over the raw artwork field. */
  private fun artworkUri(track: Track): String? = track.artworkSource?.uri ?: track.artwork?.url

  /** All metadata except artwork. */
  private fun metadataBuilder(track: Track): MediaMetadata.Builder =
    MediaMetadata.Builder()
      .setTitle(track.title)
      .setArtist(track.subtitle)
      .setAlbumTitle(track.album)
      .setDescription(track.description)
      .setGenre(track.genre)
      .setIsBrowsable(track.src == null)
      .setIsPlayable(track.src != null)
      .setExtras(MediaExtrasBuilder.build(track))
      .apply { track.favorited?.let { setUserRating(HeartRating(it)) } }

  private fun buildMediaItem(track: Track, metadata: MediaMetadata): MediaItem {
    val mediaId =
      track.url
        ?: track.src
        ?: throw IllegalArgumentException(
          "Track must have either url or src defined. Track: title='${track.title}', artist='${track.artist}'"
        )
    return MediaItem.Builder()
      .setMediaId(mediaId)
      .setUri(track.src)
      .setMediaMetadata(metadata)
      .setTag(track)
      .build()
  }
}
