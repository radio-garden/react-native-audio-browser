package com.audiobrowser.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import androidx.media3.common.MediaMetadata
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.size.Size
import coil3.svg.SvgDecoder
import coil3.toBitmap
import coil3.transform.Transformation
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
 * SVG icons are automatically tinted based on the current dark/light mode:
 * - Light mode: tinted black for visibility on light backgrounds
 * - Dark mode: tinted white for visibility on dark backgrounds
 */
object SvgArtworkRenderer {

  private const val DEFAULT_SIZE = 256 // Default size for rendered SVGs

  /**
   * Coil transformation that applies a tint color to a bitmap.
   * The tint color is included in the cache key, so different tints are cached separately.
   */
  private class TintTransformation(private val tintColor: Int) : Transformation() {
    override val cacheKey: String = "tint($tintColor)"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
      return tintBitmap(input, tintColor)
    }
  }

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
   * Always returns true (white icons for dark backgrounds).
   *
   * This renderer is used for Android Auto browse items, and Android Auto
   * has been dark-only since 2019. The DHU (Desktop Head Unit) doesn't put
   * the phone in actual car mode (UI_MODE_TYPE_CAR), so we can't detect it.
   * Since this code path is specifically for AA browse items, we always use
   * white icons which work on AA's dark background.
   */
  @Suppress("UNUSED_PARAMETER")
  private fun isDarkMode(context: Context): Boolean {
    // Android Auto has been dark-only since 2019
    // When AA gets a light theme (in progress as of 2025), this may need updating
    return true
  }

  /**
   * Applies a tint color to a bitmap using SRC_IN mode.
   * This replaces all non-transparent pixels with the tint color.
   */
  fun tintBitmap(bitmap: Bitmap, tintColor: Int): Bitmap {
    val tintedBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(tintedBitmap)
    val paint = Paint().apply {
      colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
    }
    canvas.drawBitmap(bitmap, 0f, 0f, paint)
    return tintedBitmap
  }

  /**
   * Pre-renders an SVG URL to PNG bitmap bytes with automatic tinting.
   *
   * The rendered SVG is tinted based on the current dark/light mode:
   * - Light mode: tinted black
   * - Dark mode: tinted white
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
      val darkMode = isDarkMode(context)
      val tintColor = if (darkMode) Color.WHITE else Color.BLACK
      Timber.d("Pre-rendering SVG: $url (darkMode=$darkMode)")

      // Use Coil transformation for tinting - this gets cached by Coil
      // The cache key includes URL + size + tint color
      val request = ImageRequest.Builder(context)
        .data(url)
        .size(size)
        .allowHardware(false)
        .decoderFactory { result, options, _ ->
          SvgDecoder(result.source, options)
        }
        .transformations(TintTransformation(tintColor))
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
