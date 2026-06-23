package com.audiobrowser.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.margelo.nitro.audiobrowser.ImageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A [BitmapLoader] implementation that uses Coil for image loading.
 *
 * Benefits over default Media3 BitmapLoader:
 * - Custom HTTP headers support (for authenticated CDNs)
 * - SVG support (via coil-svg)
 * - Better memory management (automatic downsampling)
 * - Shared OkHttp client for efficient connection pooling
 * - Size-aware image loading using Android Auto's artwork size hints
 *
 * @param context Android context
 * @param imageLoader Coil ImageLoader instance (should be shared app-wide)
 * @param resolveDisplayArtwork Track-first display-time artwork resolution (see
 *   Player.resolveDisplayArtwork). Returns the fetchable ImageSource for a URI Media3 hands us, or
 *   null when the URI is not ours to transform (it is then fetched as-is).
 * @param getArtworkSizeHint Callback to get the recommended artwork size in pixels from the media
 *   browser (e.g., Android Auto)
 */
@UnstableApi
class CoilBitmapLoader(
  private val context: Context,
  private val imageLoader: ImageLoader,
  private val resolveDisplayArtwork: suspend (uri: String, sizeHintPixels: Int?) -> ImageSource?,
  private val getArtworkSizeHint: () -> Int? = { null },
) : BitmapLoader {

  private val scope = CoroutineScope(Dispatchers.IO)

  /**
   * Default artwork size in pixels when no hint is provided by the media browser. Lock screen media
   * controls are ~128dp, at 4x density (xxxhdpi) = 512px.
   */
  private val defaultArtworkSizePixels = 512

  private val core = CoilArtworkLoader(context, imageLoader, defaultArtworkSizePixels)

  override fun supportsMimeType(mimeType: String): Boolean {
    return mimeType.startsWith("image/") ||
      mimeType == "image/svg+xml" ||
      mimeType == "application/svg+xml"
  }

  override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
    val future = SettableFuture.create<Bitmap>()
    try {
      val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
      if (bitmap != null) {
        future.set(bitmap)
      } else {
        future.setException(IllegalArgumentException("Failed to decode bitmap from byte array"))
      }
    } catch (e: Exception) {
      future.setException(e)
    }
    return future
  }

  override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
    val future = SettableFuture.create<Bitmap>()

    scope.launch {
      try {
        val originalUrl = uri.toString()
        // Use hint from media browser (Android Auto), or fall back to screen-based size
        val sizeHint = getArtworkSizeHint() ?: defaultArtworkSizePixels

        // Track-first resolution (registry / queue lookup). Null → the URI was not
        // produced by us (or predates the registry): fetch it as-is, never
        // re-transform a URL we cannot attribute.
        val source =
          try {
            resolveDisplayArtwork(originalUrl, sizeHint)
          } catch (e: Exception) {
            Timber.e(e, "Display artwork resolution failed for $originalUrl")
            null
          }
        val finalUrl = source?.uri ?: originalUrl
        val headers = source?.headers?.toMap() ?: emptyMap()

        // Check if this is an SVG that needs special decoding
        val isSvg = SvgArtworkRenderer.isSvgUrl(finalUrl)

        Timber.d("Loading artwork: $finalUrl (headers: ${headers.keys}, svg: $isSvg)")

        val bitmap = core.load(finalUrl, headers, sizeHint, isSvg)
        Timber.d("Loaded bitmap: ${bitmap.width}x${bitmap.height} from $finalUrl")
        future.set(bitmap)
      } catch (e: Exception) {
        Timber.e(e, "Exception loading artwork from $uri")
        future.setException(e)
      }
    }

    return future
  }
}
