package com.audiobrowser.util

import android.net.Uri

/**
 * Utility for SVG artwork detection.
 *
 * Artwork delivery for Android Auto browse items is handled via the content:// provider
 * (ArtworkContentProvider + CoilArtworkLoader), which supports SVG natively in-process. This object
 * retains [isSvgUrl] which is still used by [TrackFactory.toBrowseMediaItem] and
 * [CoilArtworkLoader] to tag registrations with the correct decoder hint.
 */
object SvgArtworkRenderer {

  /** Checks if a URL points to an SVG image. */
  fun isSvgUrl(url: String?): Boolean {
    if (url == null) return false
    return try {
      val uri = Uri.parse(url)
      uri.path?.lowercase()?.endsWith(".svg") == true
    } catch (e: Exception) {
      false
    }
  }
}
