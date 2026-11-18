package com.audiobrowser

import com.audiobrowser.util.artworkOf
import com.margelo.nitro.audiobrowser.ArtworkRendering
import com.margelo.nitro.audiobrowser.ArtworkRequestConfig
import com.margelo.nitro.audiobrowser.CardImage
import com.margelo.nitro.audiobrowser.GridTile
import com.margelo.nitro.audiobrowser.ImageQueryParams
import com.margelo.nitro.audiobrowser.ImageShape
import com.margelo.nitro.audiobrowser.ImageSource
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.NativeRouteEntry
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.SectionStyle
import com.margelo.nitro.audiobrowser.StyleDisplay
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackStyle
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import com.margelo.nitro.core.Promise

/**
 * Builders for Nitro structs, whose generated constructors take every field, so a new field breaks
 * only this file instead of every test.
 */
object TestFixtures {

  fun track(
    title: String = "T",
    id: String? = null,
    src: String? = "https://s/a.mp3",
    artwork: String? = null,
    artist: String? = null,
    album: String? = null,
    favorited: Boolean? = null,
    disabled: Boolean? = null,
    style: TrackStyle? = null,
    live: Boolean? = null,
  ) =
    Track(
      id = id,
      path = null,
      src = src,
      artwork = artworkOf(artwork),
      artworkSource = null,
      request = null,
      title = title,
      subtitle = null,
      artist = artist,
      albumPath = null,
      album = album,
      description = null,
      genre = null,
      duration = null,
      style = style,
      disabled = disabled,
      favorited = favorited,
      live = live,
      icyCharset = null,
    )

  fun section(
    children: Array<Track> = emptyArray(),
    title: String? = null,
    style: SectionStyle? = null,
    path: String? = null,
  ) = Section(title = title, style = style, path = path, children = children)

  fun transformableConfig(
    baseUrl: String? = null,
    path: String? = null,
    query: Map<String, String>? = null,
    headers: Map<String, String>? = null,
  ) =
    TransformableRequestConfig(
      transform = null,
      transformSync = null,
      method = null,
      path = path,
      baseUrl = baseUrl,
      headers = headers,
      query = query,
      body = null,
      contentType = null,
      userAgent = null,
    )

  fun mediaConfig(
    baseUrl: String? = null,
    path: String? = null,
    query: Map<String, String>? = null,
    headers: Map<String, String>? = null,
    resolveSync: ((Track) -> Promise<RequestConfig>)? = null,
  ) =
    MediaRequestConfig(
      resolve = null,
      resolveSync = resolveSync,
      transform = null,
      transformSync = null,
      method = null,
      path = path,
      baseUrl = baseUrl,
      headers = headers,
      query = query,
      body = null,
      contentType = null,
      userAgent = null,
    )

  fun browseTrack(
    title: String = "T",
    path: String = "https://api.example.com/channel/abc",
    artwork: String? = null,
    artworkSource: ImageSource? = null,
    style: TrackStyle? = null,
  ) =
    Track(
      id = null,
      path = path,
      src = null,
      artwork = artworkOf(artwork),
      artworkSource = artworkSource,
      request = null,
      title = title,
      subtitle = null,
      artist = null,
      albumPath = null,
      album = null,
      description = null,
      genre = null,
      duration = null,
      style = style,
      disabled = null,
      favorited = null,
      live = null,
      icyCharset = null,
    )

  fun resolvedTrack(
    path: String = "/container",
    title: String = "Container",
    children: Array<Track>? = null,
    sections: Array<Section>? = null,
    style: SectionStyle? = null,
  ) =
    ResolvedTrack(
      path = path,
      style = style,
      sections = sections,
      children = children,
      carPlaySiriListButton = null,
      id = null,
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
      disabled = null,
      favorited = null,
      live = null,
      icyCharset = null,
    )

  /**
   * A route serving [browseStatic] verbatim: the one browse arm that needs neither the JNI bridge
   * nor HTTP.
   */
  fun staticRoute(path: String, browseStatic: ResolvedTrack) =
    NativeRouteEntry(
      path = path,
      browseCallback = null,
      browseConfig = null,
      browseStatic = browseStatic,
      searchCallback = null,
      searchConfig = null,
      media = null,
      artwork = null,
    )

  fun artworkConfig(
    path: String? = null,
    query: Map<String, String>? = null,
    headers: Map<String, String>? = null,
    imageQueryParams: ImageQueryParams? = null,
  ) =
    ArtworkRequestConfig(
      resolve = null,
      resolveSync = null,
      transform = null,
      transformSync = null,
      imageQueryParams = imageQueryParams,
      method = null,
      path = path,
      baseUrl = null,
      headers = headers,
      query = query,
      body = null,
      contentType = null,
      userAgent = null,
    )

  fun trackStyle(
    display: StyleDisplay? = null,
    artworkRendering: ArtworkRendering? = null,
    imageShape: ImageShape? = null,
    accessorySymbol: String? = null,
    cardTint: String? = null,
    cardImage: CardImage? = null,
  ) =
    TrackStyle(
      display = display,
      artworkRendering = artworkRendering,
      imageShape = imageShape,
      accessorySymbol = accessorySymbol,
      cardTint = cardTint,
      cardImage = cardImage,
    )

  fun sectionStyle(
    display: StyleDisplay? = null,
    artworkRendering: ArtworkRendering? = null,
    imageShape: ImageShape? = null,
    accessorySymbol: String? = null,
    cardTint: String? = null,
    cardImage: CardImage? = null,
    gridWrap: Boolean? = null,
    gridTile: GridTile? = null,
  ) =
    SectionStyle(
      gridWrap = gridWrap,
      gridTile = gridTile,
      display = display,
      artworkRendering = artworkRendering,
      imageShape = imageShape,
      accessorySymbol = accessorySymbol,
      cardTint = cardTint,
      cardImage = cardImage,
    )
}
