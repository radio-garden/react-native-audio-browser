package com.audiobrowser.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaMetadata
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.svg.SvgDecoder
import coil3.toBitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Utility for pre-rendering SVG artwork to bitmaps for Android Auto compatibility.
 *
 * Android Auto loads artwork from URLs directly and doesn't support SVG format.
 * This class pre-renders SVG images to PNG bitmaps that can be embedded in MediaMetadata
 * using setArtworkData().
 *
 * Note: SVGs are rendered as-is without tinting. Content providers should provide
 * appropriately colored icons for Android Auto's dark UI (e.g., white icons).
 */
object SvgArtworkRenderer {

  private const val DEFAULT_SIZE = 256 // Default size for rendered SVGs

  /**
   * Checks if a URL points to an SVG image.
   */
  fun isSvgUrl(url: String?): Boolean {
    if (url == null) return false
    return try {
      val uri = Uri.parse(url)
      uri.path?.lowercase()?.endsWith(".svg") == true
    } catch (e: Exception) {
      false
    }
  }

  /**
   * Pre-renders an SVG URL to PNG bitmap bytes.
   *
   * The SVG is rendered as-is without color modification. Content providers
   * should provide icons in the appropriate color for the target UI (e.g.,
   * white icons for Android Auto's dark background).
   *
   * @param context Android context
   * @param imageLoader Coil ImageLoader instance
   * @param url The SVG URL to render
   * @param size Target size in pixels (width and height)
   * @return PNG bitmap bytes, or null if rendering fails
   */
  suspend fun renderSvgToBytes(
    context: Context,
    imageLoader: ImageLoader,
    url: String,
    size: Int = DEFAULT_SIZE,
  ): ByteArray? = withContext(Dispatchers.IO) {
    try {
      Timber.d("Pre-rendering SVG: $url")

      val request = ImageRequest.Builder(context)
        .data(url)
        .size(size)
        .allowHardware(false)
        .decoderFactory { result, options, _ ->
          SvgDecoder(result.source, options)
        }
        .build()

      val result = imageLoader.execute(request)
      val bitmap = result.image?.toBitmap()

      if (bitmap != null) {
        Timber.d("SVG rendered successfully: ${bitmap.width}x${bitmap.height}")

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.toByteArray()
      } else {
        Timber.e("Failed to render SVG: result.image was null")
        null
      }
    } catch (e: Exception) {
      Timber.e(e, "Error pre-rendering SVG: $url")
      null
    }
  }

  /**
   * Applies artwork to MediaMetadata.Builder, pre-rendering SVGs as needed.
   *
   * For SVG URLs, this renders the image to PNG and uses setArtworkData().
   * For other URLs, this uses setArtworkUri() as normal.
   *
   * @param builder The MediaMetadata.Builder to modify
   * @param artworkUrl The artwork URL (may be SVG or other format)
   * @param context Android context (required for SVG rendering)
   * @param imageLoader Coil ImageLoader (required for SVG rendering)
   * @return The modified builder
   */
  suspend fun applyArtwork(
    builder: MediaMetadata.Builder,
    artworkUrl: String?,
    context: Context?,
    imageLoader: ImageLoader?,
  ): MediaMetadata.Builder {
    if (artworkUrl == null) {
      return builder
    }

    // Check if this is an SVG that needs pre-rendering
    if (isSvgUrl(artworkUrl) && context != null && imageLoader != null) {
      val bytes = renderSvgToBytes(context, imageLoader, artworkUrl)
      if (bytes != null) {
        Timber.d("Using pre-rendered SVG artwork (${bytes.size} bytes)")
        return builder.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
      }
      // Fall through to setArtworkUri if rendering fails
      Timber.w("SVG pre-rendering failed, falling back to URI")
    }

    // Use URI for non-SVG or if SVG rendering failed
    return builder.setArtworkUri(Uri.parse(artworkUrl))
  }
}
