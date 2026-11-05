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
 * Media3 [BitmapLoader] that fetches artwork through Coil, with request headers, SVG and
 * downsampling to the media browser's size hint.
 *
 * @param resolveDisplayArtwork maps a URI Media3 hands over to the source to fetch (see
 *   Player.resolveDisplayArtwork); null means the URI is not the library's to rewrite and is
 *   fetched as-is. If it throws, the URI is also fetched as-is.
 * @param getArtworkSizeHint the size in pixels the connected media browser (e.g. Android Auto) asks
 *   for, or null for none.
 */
@UnstableApi
class CoilBitmapLoader(
  private val context: Context,
  private val imageLoader: ImageLoader,
  private val resolveDisplayArtwork: suspend (uri: String, sizeHintPixels: Int?) -> ImageSource?,
  private val getArtworkSizeHint: () -> Int? = { null },
) : BitmapLoader {

  private val scope = CoroutineScope(Dispatchers.IO)

  // Lock screen controls are ~128dp; at 4x density (xxxhdpi) that is 512px.
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
        val sizeHint = getArtworkSizeHint() ?: defaultArtworkSizePixels

        val source =
          try {
            resolveDisplayArtwork(originalUrl, sizeHint)
          } catch (e: Exception) {
            Timber.e(e, "Display artwork resolution failed for $originalUrl")
            null
          }
        val finalUrl = source?.uri ?: originalUrl
        val headers = source?.headers?.toMap() ?: emptyMap()

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
