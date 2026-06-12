package com.audiobrowser.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.svg.SvgDecoder
import coil3.toBitmap
import com.audiobrowser.http.RequestConfigBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.margelo.nitro.audiobrowser.ArtworkRequestConfig
import com.margelo.nitro.audiobrowser.ImageContext
import com.margelo.nitro.audiobrowser.ImageSource
import com.margelo.nitro.audiobrowser.MediaTransformParams
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
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
 * @param getArtworkConfig Callback to get artwork configuration for URL transformation
 * @param getArtworkSizeHint Callback to get the recommended artwork size in pixels from the media
 *   browser (e.g., Android Auto)
 */
@UnstableApi
class CoilBitmapLoader(
  private val context: Context,
  private val imageLoader: ImageLoader,
  private val getArtworkConfig: suspend () -> ArtworkConfig?,
  private val getArtworkSizeHint: () -> Int? = { null },
) : BitmapLoader {

  private val scope = CoroutineScope(Dispatchers.IO)

  /**
   * Default artwork size in pixels when no hint is provided by the media browser. Lock screen media
   * controls are ~128dp, at 4x density (xxxhdpi) = 512px.
   */
  private val defaultArtworkSizePixels = 512

  /** Configuration for artwork requests including headers and URL transformation. */
  data class ArtworkConfig(
    val requestConfig: TransformableRequestConfig?,
    val artworkConfig: ArtworkRequestConfig?,
  )

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
        val artworkUrl = uri.toString()
        // Use hint from media browser (Android Auto), or fall back to screen-based size
        val sizeHint = getArtworkSizeHint() ?: defaultArtworkSizePixels

        val (finalUrl, headers) = transformArtworkUrl(artworkUrl, sizeHint)

        // Check if this is an SVG that needs special decoding
        val isSvg = SvgArtworkRenderer.isSvgUrl(finalUrl)

        Timber.d("Loading artwork: $finalUrl (headers: ${headers.keys}, svg: $isSvg)")

        val requestBuilder =
          ImageRequest.Builder(context)
            .data(finalUrl)
            .allowHardware(false) // Required for Media3 notification compatibility

        // Add custom headers if present
        if (headers.isNotEmpty()) {
          val networkHeaders = NetworkHeaders.Builder()
          headers.forEach { (key, value) -> networkHeaders.add(key, value) }
          requestBuilder.httpHeaders(networkHeaders.build())
        }

        // Force SVG decoder for .svg URLs (Coil's auto-detection can fail with some CDNs)
        if (isSvg) {
          requestBuilder.decoderFactory { result, options, _ -> SvgDecoder(result.source, options) }
        }

        val result = imageLoader.execute(requestBuilder.build())
        val bitmap = result.image?.toBitmap()

        if (bitmap != null) {
          Timber.d("Loaded bitmap: ${bitmap.width}x${bitmap.height} from $finalUrl")
          future.set(bitmap)
        } else {
          Timber.e("Failed to decode image from $finalUrl - result.image was null")
          future.setException(IllegalStateException("Failed to load bitmap from $finalUrl"))
        }
      } catch (e: Exception) {
        Timber.e(e, "Exception loading artwork from $uri")
        future.setException(e)
      }
    }

    return future
  }

  /**
   * Transforms an artwork URL using the configured artwork request config.
   *
   * Applies:
   * - Base URL transformation
   * - Custom headers (e.g., Authorization, API keys)
   * - Query parameters (e.g., signed tokens)
   * - Size query parameters from imageQueryParams config (if sizeHintPixels provided)
   *
   * @param originalUrl The original artwork URL from track metadata
   * @param sizeHintPixels Optional size hint in pixels from Android Auto
   * @return Pair of (transformedUrl, headers)
   */
  private suspend fun transformArtworkUrl(
    originalUrl: String,
    sizeHintPixels: Int? = null,
  ): Pair<String, Map<String, String>> {
    val config = getArtworkConfig()

    // No config - return original URL with no headers
    if (config == null || config.artworkConfig == null) {
      return originalUrl to emptyMap()
    }

    return try {
      val artworkConfig = config.artworkConfig

      // Base via the shared request layer (its transform runs for artwork too),
      // with the original URL as path.
      var mergedBaseConfig = RequestConfig(null, originalUrl, null, null, null, null, null, null)
      config.requestConfig?.let {
        mergedBaseConfig = RequestConfigBuilder.mergeConfig(mergedBaseConfig, it, emptyMap())
      }

      // Create ImageContext from size hint if available
      val imageContext =
        sizeHintPixels?.takeIf { it > 0 }?.let { ImageContext(it.toDouble(), it.toDouble()) }

      // Apply image query params BEFORE transform (so transform can override)
      val queryParams = artworkConfig.imageQueryParams
      if (imageContext != null && queryParams != null) {
        val contextQuery = mutableMapOf<String, String>()
        queryParams.width?.let { key ->
          imageContext.width?.let { contextQuery[key] = it.toInt().toString() }
        }
        queryParams.height?.let { key ->
          imageContext.height?.let { contextQuery[key] = it.toInt().toString() }
        }

        if (contextQuery.isNotEmpty()) {
          Timber.d("Adding image query params: $contextQuery")
          val existingQuery = mergedBaseConfig.query?.toMutableMap() ?: mutableMapOf()
          existingQuery.putAll(contextQuery)
          mergedBaseConfig = mergedBaseConfig.copy(query = existingQuery)
        }
      }

      // Apply artwork transformation (transform can override imageQueryParams)
      val finalConfig =
        RequestConfigBuilder.mergeConfig(mergedBaseConfig, artworkConfig, imageContext)

      // Build final URL
      val finalUrl =
        RequestConfigBuilder.buildUrl(RequestConfigBuilder.toRequestConfig(finalConfig))

      // Extract headers
      val headers = finalConfig.headers?.toMap() ?: emptyMap()

      finalUrl to headers
    } catch (e: Exception) {
      Timber.e(e, "Failed to transform artwork URL: $originalUrl")
      originalUrl to emptyMap()
    }
  }
}
