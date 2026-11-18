package com.audiobrowser.util

import android.net.Uri
import com.audiobrowser.browser.BrowseArtworkRegistry
import com.audiobrowser.browser.ResolvedArtwork

/** Routes artwork URLs through the [ArtworkContentProvider] at [authority], via its [registry]. */
class ArtworkRoutes(val registry: BrowseArtworkRegistry, val authority: String) {
  /**
   * Registers http(s) [url] and returns the `content://` URI the provider serves it at; any other
   * URL comes back unchanged. [pinned] artwork survives [BrowseArtworkRegistry.clear]; pinning one
   * of this provider's own URIs re-pins the artwork it already holds.
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
   * The URL to show JS for [uri]: for this provider's URIs the URL it serves, or null once the
   * registry has dropped it; any other URI as its string.
   */
  fun publicUrl(uri: Uri): String? {
    if (uri.authority != authority) return uri.toString()
    return ArtworkUris.parseToken(uri)?.let { registry.lookup(it)?.finalUrl }
  }
}
