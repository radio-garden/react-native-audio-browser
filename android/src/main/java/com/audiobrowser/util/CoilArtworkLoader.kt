package com.audiobrowser.util

import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.svg.SvgDecoder
import coil3.toBitmap

/**
 * Shared "resolved source → Bitmap" core. Used by both [CoilBitmapLoader] (now-playing) and
 * [ArtworkContentProvider] (browse). Adds `.size()` so raster decodes are downsampled to the hint
 * (the prior loadBitmap path decoded at full resolution). SVG is forced via [isSvg], carried from
 * build time rather than re-derived from a possibly-suffixless transformed URL.
 */
class CoilArtworkLoader(
  private val context: Context,
  private val imageLoader: ImageLoader,
  private val defaultSizePixels: Int = 512,
) {
  suspend fun load(
    finalUrl: String,
    headers: Map<String, String>?,
    sizeHintPixels: Int?,
    isSvg: Boolean,
  ): Bitmap {
    val builder =
      ImageRequest.Builder(context)
        .data(finalUrl)
        .size(sizeHintPixels ?: defaultSizePixels)
        .allowHardware(false) // required for Media3 notification compatibility

    if (!headers.isNullOrEmpty()) {
      val net = NetworkHeaders.Builder()
      headers.forEach { (k, v) -> net.add(k, v) }
      builder.httpHeaders(net.build())
    }
    if (isSvg) {
      builder.decoderFactory { result, options, _ -> SvgDecoder(result.source, options) }
    }

    val result = imageLoader.execute(builder.build())
    return result.image?.toBitmap()
      ?: throw IllegalStateException("Failed to decode artwork from $finalUrl")
  }
}
