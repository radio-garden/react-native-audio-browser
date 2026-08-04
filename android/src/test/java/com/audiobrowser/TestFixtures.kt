package com.audiobrowser

import com.margelo.nitro.audiobrowser.ArtworkRequestConfig
import com.margelo.nitro.audiobrowser.ImageQueryParams
import com.margelo.nitro.audiobrowser.ImageSource
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TransformableRequestConfig

/**
 * Shared builders for Nitro structs, whose generated constructors require every field. Tests set
 * only what they assert on; a new Track/config field breaks exactly this file instead of every
 * test.
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
  ) =
    Track(
      id = id,
      url = null,
      src = src,
      artwork = artwork,
      artworkSource = null,
      request = null,
      artworkCarPlayTinted = null,
      title = title,
      subtitle = null,
      artist = artist,
      albumUrl = null,
      album = album,
      description = null,
      genre = null,
      duration = null,
      style = null,
      childrenStyle = null,
      favorited = favorited,
      groupTitle = null,
      live = null,
      imageRow = null,
    )

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
  ) =
    MediaRequestConfig(
      resolve = null,
      resolveSync = null,
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

  /**
   * Builds a minimal browse-only Track (no src → browsable, not playable). Pass [artworkSource] for
   * a pre-resolved HTTP artwork URL or [artwork] for a raw resource/file URI; omit both for no
   * artwork.
   */
  fun browseTrack(
    title: String = "T",
    url: String = "https://api.example.com/channel/abc",
    artwork: String? = null,
    artworkSource: ImageSource? = null,
  ) =
    Track(
      id = null,
      url = url,
      src = null, // browsable, not playable
      artwork = artwork,
      artworkSource = artworkSource,
      request = null,
      artworkCarPlayTinted = null,
      title = title,
      subtitle = null,
      artist = null,
      albumUrl = null,
      album = null,
      description = null,
      genre = null,
      duration = null,
      style = null,
      childrenStyle = null,
      favorited = null,
      groupTitle = null,
      live = null,
      imageRow = null,
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
}
