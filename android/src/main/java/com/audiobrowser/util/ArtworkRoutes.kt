package com.audiobrowser.util

import android.net.Uri
import com.audiobrowser.browser.BrowseArtworkRegistry
import com.audiobrowser.browser.ResolvedArtwork

/** Routes artwork URLs through the [ArtworkContentProvider]: its registry and authority. */
class ArtworkRoutes(val registry: BrowseArtworkRegistry, val authority: String) {
  /**
   * The `content://` URI that serves http(s) [url], registered with the provider; any other URI as
   * it is. A [pinned] registration survives the registry's clear, as does a pinned URI of this
   * provider's own.
   */
  fun route(url: String, headers: Map<String, String>?, isSvg: Boolean, pinned: Boolean): String {
    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
      if (pinned && uri.authority == authority) {
        ArtworkUris.parseToken(uri)?.let { token ->
          registry.lookup(token)?.let { registry.pin(token, it) }
        }
      }
      return url
    }
    val token = ArtworkUris.tokenFor(url)
    val artwork = ResolvedArtwork(url, headers, isSvg)
    if (pinned) registry.pin(token, artwork) else registry.register(token, artwork)
    return ArtworkUris.contentUri(authority, token)
  }

  /**
   * The URL a URI shows to JS: for one of this provider's URIs the URL it serves, or null once the
   * registry has forgotten it; any other URI as it is.
   */
  fun publicUrl(uri: Uri): String? {
    if (uri.authority != authority) return uri.toString()
    return ArtworkUris.parseToken(uri)?.let { registry.lookup(it)?.finalUrl }
  }
}
