package com.audiobrowser.util

import android.net.Uri

/** Detects SVG URLs only; SVG decoding happens in [CoilArtworkLoader] through Coil's SvgDecoder. */
object SvgArtworkRenderer {

  /**
   * Whether the URL is an `image/svg+xml` `data:` URL, or its path ends in `.svg`, ignoring case
   * and any query. An SVG served from a path without that extension reads as false.
   */
  fun isSvgUrl(url: String?): Boolean {
    if (url == null) return false
    if (url.startsWith("data:image/svg+xml", ignoreCase = true)) return true
    return try {
      val uri = Uri.parse(url)
      uri.path?.lowercase()?.endsWith(".svg") == true
    } catch (e: Exception) {
      false
    }
  }
}
