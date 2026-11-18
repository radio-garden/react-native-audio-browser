package com.audiobrowser.util

import android.net.Uri

/** Detects SVG URLs only; SVG decoding happens in [CoilArtworkLoader] through Coil's SvgDecoder. */
object SvgArtworkRenderer {

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
