package com.audiobrowser.util

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.audiobrowser.browser.BrowseArtworkRegistry
import com.audiobrowser.browser.ResolvedArtwork
import com.margelo.nitro.audiobrowser.ImageRowItem
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackStyle
import com.margelo.nitro.audiobrowser.Variant_String_ArtworkVariants

/**
 * The single Track → Media3 [MediaItem] conversion. Owns the two easy-to-drift fallbacks: the
 * displayed artwork is the transformed `artworkSource.uri` falling back to the raw `artwork` field,
 * and the mediaId is the stable `id` for playable tracks, falling back to `url` then `src` (a Track
 * must have one of the latter two — see the `src` vs `url` note in CONTEXT.md). The list line
 * renders from `subtitle` (the now-playing line is re-stamped from `artist` by the now-playing
 * pipeline; see the Now Playing Metadata guide).
 */
object TrackFactory {
  fun fromMedia3(mediaItem: MediaItem): Track {
    return mediaItem.localConfiguration!!.tag as Track
  }

  /**
   * Android Auto has no image-row rendering; its closest equivalent is a grid of artwork
   * tiles. A track carrying `imageRow` expands into its items as grid-styled rows (the
   * per-item content-style hint — hosts that ignore it fall back to list rows) grouped under
   * the row's title, followed by the row itself as a browsable "view all" link when it has a
   * `url` (a url-less row is a pure preview and contributes only its items). Tracks without
   * an `imageRow` pass through unchanged.
   */
  fun expandImageRows(tracks: List<Track>): List<Track> =
    tracks.flatMap { track ->
      val items = track.imageRow
      if (items.isNullOrEmpty()) return@flatMap listOf(track)
      val expanded = items.map { it.toTrack(groupTitle = track.title) }
      if (track.url != null) {
        expanded + track.copy(imageRow = null, groupTitle = track.title)
      } else {
        expanded
      }
    }

  /** The row-item equivalent of a full Track, for surfaces that render items as plain rows. */
  fun ImageRowItem.toTrack(groupTitle: String?): Track =
    Track(
      id = id,
      url = url,
      src = src,
      artwork = artwork?.let { Variant_String_ArtworkVariants.First(it) },
      artworkSource = artworkSource,
      request = request,
      artworkCarPlayTinted = null,
      title = title,
      subtitle = null,
      artist = artist,
      albumUrl = albumUrl,
      album = album,
      description = null,
      genre = null,
      duration = null,
      style = TrackStyle.GRID,
      childrenStyle = null,
      favorited = null,
      groupTitle = groupTitle,
      live = live,
      imageRow = null,
    )

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
    // A playable track's mediaId is the consumer's stable `id` when it has one.
    // Android Auto marks the "now playing" browse row by exact mediaId equality
    // between the row and the player's current item (media3 announces
    // `currentMediaItem.mediaId` in the legacy playback state), and a
    // consumer-loaded track's `src` can differ textually from the browse row's
    // for the same item (absolute vs relative, extra query params) — the id is
    // the one string both sides share. It is also context-free, so the same
    // station gets the same mediaId in every tab. Browsable-only tracks keep
    // `url` so navigation parentIds stay resolvable paths.
    val stableId = if (track.src != null) track.id?.takeUnless { it.isBlank() } else null
    val mediaId =
      stableId
        ?: track.url
        ?: track.src
        ?: throw IllegalArgumentException(
          "Track must have either url or src defined. Track: title='${track.title}', artist='${track.artist}'"
        )
    return MediaItem.Builder()
      .setMediaId(mediaId)
      .setUri(track.src)
      // The playable uri also rides in requestMetadata: a controller replaying
      // this item after process death (track cache empty) round-trips only
      // mediaId + requestMetadata, and a stable-id mediaId is not playable by
      // itself — resolveMediaItemToTrack rebuilds a minimal track from this.
      .setRequestMetadata(
        MediaItem.RequestMetadata.Builder().setMediaUri(track.src?.toUri()).build()
      )
      .setMediaMetadata(metadata)
      .setTag(track)
      .build()
  }
}
