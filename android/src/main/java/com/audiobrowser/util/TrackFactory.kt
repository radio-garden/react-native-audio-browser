package com.audiobrowser.util

import androidx.core.net.toUri
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.audiobrowser.browser.BrowseArtworkRegistry
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackStyle

/**
 * The single Track → Media3 [MediaItem] conversion. Owns the two easy-to-drift fallbacks: the
 * displayed artwork is the transformed `artworkSource.uri` falling back to the raw `artwork` field,
 * and the mediaId is the stable `id` for playable tracks, falling back to `path` then `src` (a
 * Track must have one of the latter two — see the `src` vs `path` note in CONTEXT.md). The list
 * line renders from `subtitle` (the now-playing line is re-stamped from `artist` by the now-playing
 * pipeline; see the Now Playing Metadata guide).
 */
object TrackFactory {
  /** Set once by the Player. */
  @Volatile var artworkRoutes: ArtworkRoutes? = null

  /**
   * The artwork URI the session publishes for [url]. On Android Automotive OS, whose car media app
   * loads artwork only from `content://`, `android.resource://` and `file://` URIs, http(s) artwork
   * is served through the provider, as browse artwork always is.
   */
  fun sessionArtworkUri(url: String, headers: Map<String, String>?, isSvg: Boolean): String {
    val provider = artworkRoutes?.takeIf { Automotive.device } ?: return url
    return provider.route(url, headers, isSvg, pinned = true)
  }

  /**
   * Stamps [favorited] as the heart `userRating`, which a controller's rating sets: Android
   * Automotive OS's car media app draws it as its own favorite control.
   */
  fun MediaMetadata.Builder.setFavorite(favorited: Boolean?): MediaMetadata.Builder = apply {
    if (favorited != null) setUserRating(HeartRating(favorited))
  }

  fun fromMedia3(mediaItem: MediaItem): Track {
    return mediaItem.localConfiguration!!.tag as Track
  }

  /**
   * A synthetic browsable Track for a section's "view more" row — the section has a path but no
   * Track to hang it on (ADR 0010). It carries the section's declared item block as its own,
   * `display` included: the section's declaration about this content's layout is the only declared
   * promise the "view all" page can ever have (there is no consumer-authored handle to declare one
   * on), and without it Android Auto would always render that page as a list.
   *
   * [title] is the consumer's `viewMoreTitle`, one string for every section — not the section's own
   * title, which the group header above the row already shows.
   */
  fun navigationTrack(section: Section, title: String): Track =
    Track(
      id = null,
      path = section.path,
      src = null,
      artwork = null,
      artworkSource = null,
      request = null,
      title = title,
      subtitle = null,
      artist = null,
      albumPath = null,
      album = null,
      description = null,
      genre = null,
      duration = null,
      style =
        section.style?.let {
          TrackStyle(
            display = it.display,
            artworkRendering = it.artworkRendering,
            imageShape = it.imageShape,
            accessorySymbol = it.accessorySymbol,
            cardTint = it.cardTint,
            cardImage = it.cardImage,
          )
        },
      disabled = null,
      favorited = null,
      live = null,
      icyCharset = null,
    )

  fun toMedia3(tracks: Array<Track>): List<MediaItem> {
    return tracks.map { toMedia3(it) }
  }

  fun toMedia3(track: Track): MediaItem {
    val artwork =
      artworkUri(track)?.let { sessionArtworkUri(it, track.artworkSource?.headers, isSvg(track)) }
    val metadata = metadataBuilder(track).setArtworkUri(artwork?.toUri()).build()
    return buildMediaItem(track, metadata)
  }

  /**
   * Browse-surface conversion. Routes http(s) artwork through the content:// provider (so headers +
   * SVG apply in our process, and no bytes cross the Binder), registering it in [registry].
   * Non-http artwork (android.resource:// tab icons, file://) passes through to setArtworkUri
   * unchanged so vector/category icons survive.
   */
  fun toBrowseMediaItem(
    track: Track,
    registry: BrowseArtworkRegistry,
    authority: String,
    section: Section? = null,
  ): MediaItem {
    val artwork =
      artworkUri(track)?.let {
        ArtworkRoutes(registry, authority)
          .route(it, track.artworkSource?.headers, isSvg(track), pinned = false)
      }
    val metadata = metadataBuilder(track, section).setArtworkUri(artwork?.toUri()).build()
    return buildMediaItem(track, metadata)
  }

  private fun isSvg(track: Track): Boolean =
    SvgArtworkRenderer.isSvgUrl(artworkUri(track)) ||
      SvgArtworkRenderer.isSvgUrl(track.artwork?.url)

  /** The transformed artworkSource wins over the raw artwork field. */
  private fun artworkUri(track: Track): String? = track.artworkSource?.uri ?: track.artwork?.url

  /**
   * All metadata except artwork. The owning [section] (browse surfaces only) supplies the
   * group-title header hint and the per-item style default (ADR 0010).
   */
  private fun metadataBuilder(track: Track, section: Section? = null): MediaMetadata.Builder =
    MediaMetadata.Builder()
      .setTitle(track.title)
      .setArtist(track.subtitle)
      .setAlbumTitle(track.album)
      .setDescription(track.description)
      .setGenre(track.genre)
      .setIsBrowsable(track.src == null)
      .setIsPlayable(track.src != null)
      .setExtras(MediaExtrasBuilder.build(track, section))
      .setFavorite(track.favorited)

  private fun buildMediaItem(track: Track, metadata: MediaMetadata): MediaItem {
    // A playable track's mediaId is the consumer's stable `id` when it has one —
    // the identity rule (id when non-blank, else src; see Track.identity /
    // ADR 0008), except that an id-less playable track prefers its contextual
    // `path` over `src` here, so this is deliberately NOT expressed via
    // Track.identity. Android Auto marks the "now playing" browse row by exact
    // mediaId equality between the row and the player's current item (media3
    // announces `currentMediaItem.mediaId` in the legacy playback state), and a
    // consumer-loaded track's `src` can differ textually from the browse row's
    // for the same item (absolute vs relative, extra query params) — the id is
    // the one string both sides share. It is also context-free, so the same
    // station gets the same mediaId in every tab. Browsable-only tracks keep
    // `path` so navigation parentIds stay resolvable paths.
    val stableId = if (track.src != null) track.id?.takeUnless { it.isBlank() } else null
    val mediaId =
      stableId
        ?: track.path
        ?: track.src
        ?: throw IllegalArgumentException(
          "Track must have either path or src defined. Track: title='${track.title}', artist='${track.artist}'"
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
