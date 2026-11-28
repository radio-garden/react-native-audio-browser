package com.audiobrowser.util

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.audiobrowser.R
import com.audiobrowser.browser.BrowseArtworkRegistry
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.StyleDisplay
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackStyle

/**
 * The one Track → Media3 [MediaItem] conversion. A Track needs `path` or `src`; see the `src` vs
 * `path` note in GLOSSARY.md.
 */
object TrackFactory {
  @Volatile var artworkRoutes: ArtworkRoutes? = null

  /**
   * The artwork URI the session publishes for [url]: a `content://` URI from the provider for a
   * `data:` URL, which would otherwise send its bytes to every controller; on Android Automotive
   * OS, whose car media app loads artwork only from `content://`, `android.resource://` and
   * `file://` URIs, for any URL the provider serves; else [url] itself.
   */
  fun sessionArtworkUri(url: String, headers: Map<String, String>?, isSvg: Boolean): String {
    val provider = artworkRoutes ?: return url
    if (!Automotive.device && !BrowserPathHelper.isDataUrl(url)) return url
    return provider.route(url, headers, isSvg, pinned = true)
  }

  @Volatile var favoriteEnabled = false

  /**
   * Sets the heart user rating: [favorited], or unrated when that is unknown on a [playable] item,
   * so Android Automotive OS, which draws its own favorite control from it, always has one to draw.
   * None while [favoriteEnabled] is off.
   */
  fun MediaMetadata.Builder.setFavorite(
    favorited: Boolean?,
    playable: Boolean = true,
  ): MediaMetadata.Builder =
    setUserRating(
      when {
        !favoriteEnabled -> null
        favorited != null -> HeartRating(favorited)
        playable -> HeartRating()
        else -> null
      }
    )

  /** Throws unless [mediaItem] was built here, which tags it with its Track. */
  fun fromMedia3(mediaItem: MediaItem): Track {
    return mediaItem.localConfiguration!!.tag as Track
  }

  /**
   * A browsable Track for a section's "view more" row (ADR 0010). It carries the section's style,
   * `display` included: nothing else can declare the layout of the page it opens, and without it
   * Android Auto renders that page as a list. It is titled by the section, since car media apps
   * head the opened page with the tapped row's title, and subtitled by [viewMoreTitle], which a
   * section without a title takes as its title.
   */
  fun navigationTrack(section: Section, viewMoreTitle: String): Track {
    val sectionTitle = section.title?.takeIf { it.isNotBlank() }
    return Track(
      id = null,
      path = section.path,
      src = null,
      artwork = null,
      artworkSource = null,
      request = null,
      title = sectionTitle ?: viewMoreTitle,
      subtitle = viewMoreTitle.takeIf { sectionTitle != null },
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
  }

  /**
   * A section's "view more" row (see [navigationTrack]). In a grid section it is a tile, which
   * Android Auto draws as a broken image when it has no artwork. It carries [artwork] when the
   * provider can serve it, filling the tile in its own colours; else the library's arrow icon from
   * [packageName]'s resources, which the car tints. Both go through [packageName]'s provider,
   * registered in [registry], which draws the icon at the car's size hint; the car would draw a
   * resource URI at its intrinsic size.
   */
  fun toNavigationMediaItem(
    section: Section,
    viewMoreTitle: String,
    artwork: String?,
    registry: BrowseArtworkRegistry,
    packageName: String,
  ): MediaItem {
    val track = navigationTrack(section, viewMoreTitle)
    val url =
      if (section.style?.display == StyleDisplay.GRID) {
        artwork?.takeIf { ArtworkUris.isServable(it.toUri(), packageName) }
          ?: "android.resource://$packageName/${R.drawable.audio_browser_view_more}"
      } else {
        null
      }
    val icon =
      url?.let {
        ArtworkRoutes(registry, packageName)
          .route(it, null, SvgArtworkRenderer.isSvgUrl(it), pinned = false)
      }
    val metadata =
      metadataBuilder(track, MediaExtrasBuilder.buildNavigation(track, section, url))
        .setArtworkUri(icon?.toUri())
        .build()
    return buildMediaItem(track, metadata)
  }

  fun toMedia3(tracks: Array<Track>): List<MediaItem> {
    return tracks.map { toMedia3(it) }
  }

  fun toMedia3(track: Track): MediaItem {
    val artwork =
      artworkUri(track)?.let { sessionArtworkUri(it, track.artworkSource?.headers, isSvg(track)) }
    val metadata =
      metadataBuilder(track, MediaExtrasBuilder.build(track))
        .setArtworkUri(artwork?.toUri())
        .build()
    return buildMediaItem(track, metadata)
  }

  /**
   * Routes [ArtworkUris.isServable] artwork through [packageName]'s `content://` provider,
   * registered in [registry], so headers and SVG rendering apply in this process and no bytes cross
   * the Binder. Other artwork (another package's resources, resources by name, `file://`) passes
   * through unchanged.
   */
  fun toBrowseMediaItem(
    track: Track,
    registry: BrowseArtworkRegistry,
    packageName: String,
    section: Section? = null,
  ): MediaItem {
    val artwork =
      artworkUri(track)?.let {
        ArtworkRoutes(registry, packageName)
          .route(it, track.artworkSource?.headers, isSvg(track), pinned = false)
      }
    val metadata =
      metadataBuilder(track, MediaExtrasBuilder.build(track, section))
        .setArtworkUri(artwork?.toUri())
        .build()
    return buildMediaItem(track, metadata)
  }

  private fun isSvg(track: Track): Boolean =
    SvgArtworkRenderer.isSvgUrl(artworkUri(track)) ||
      SvgArtworkRenderer.isSvgUrl(track.artwork?.url)

  private fun artworkUri(track: Track): String? = track.artworkSource?.uri ?: track.artwork?.url

  private fun metadataBuilder(track: Track, extras: Bundle): MediaMetadata.Builder =
    MediaMetadata.Builder()
      .setTitle(track.title)
      // The list line shows `subtitle`; the now-playing pipeline re-stamps it from `artist`.
      .setArtist(track.subtitle)
      .setAlbumTitle(track.album)
      .setDescription(track.description)
      .setGenre(track.genre)
      .setIsBrowsable(track.src == null)
      .setIsPlayable(track.src != null)
      .setExtras(extras)
      .setFavorite(track.favorited, playable = track.src != null)

  private fun buildMediaItem(track: Track, metadata: MediaMetadata): MediaItem {
    // Android Auto marks the now-playing browse row by exact mediaId equality with the player's
    // current item, and a loaded track's `src` can differ textually from the row's, so a playable
    // track uses its `id`, then `path`, deliberately not Track.identity (ADR 0008). The id is also
    // context-free: the same item gets the same mediaId in every tab. Browsable tracks keep `path`
    // so navigation parentIds stay resolvable.
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
      // A controller replaying this item after process death sends back only mediaId and
      // requestMetadata, and an id mediaId cannot be played on its own: resolveMediaItemToTrack
      // rebuilds the track from this.
      .setRequestMetadata(
        MediaItem.RequestMetadata.Builder().setMediaUri(track.src?.toUri()).build()
      )
      .setMediaMetadata(metadata)
      .setTag(track)
      .build()
  }
}
