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
import coil3.toBitmap
import com.audiobrowser.http.RequestConfigBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * A [BitmapLoader] implementation that uses Coil for image loading.
 *
 * Benefits over default Media3 BitmapLoader:
 * - Custom HTTP headers support (for authenticated CDNs)
 * - SVG support (via coil-svg)
 * - Better memory management (automatic downsampling)
 * - Shared OkHttp client for efficient connection pooling
 *
 * @param context Android context
 * @param imageLoader Coil ImageLoader instance (should be shared app-wide)
 * @param getArtworkConfig Callback to get artwork configuration for URL transformation
 */
@UnstableApi
class CoilBitmapLoader(
  private val context: Context,
  private val imageLoader: ImageLoader,
  private val getArtworkConfig: () -> ArtworkConfig?,
) : BitmapLoader {

  private val scope = CoroutineScope(Dispatchers.IO)

  /**
   * Configuration for artwork requests including headers and URL transformation.
   */
  data class ArtworkConfig(
    val baseConfig: RequestConfig?,
    val artworkConfig: MediaRequestConfig?,
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
        val (finalUrl, headers) = transformArtworkUrl(uri.toString())

        Timber.d("Loading artwork: $finalUrl (headers: ${headers.keys})")

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

        val result = imageLoader.execute(requestBuilder.build())
        val bitmap = result.image?.toBitmap()

        if (bitmap != null) {
          future.set(bitmap)
        } else {
          future.setException(IllegalStateException("Failed to load bitmap from $finalUrl"))
        }
      } catch (e: Exception) {
        Timber.e(e, "Failed to load artwork from $uri")
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
   *
   * @param originalUrl The original artwork URL from track metadata
   * @return Pair of (transformedUrl, headers)
   */
  private suspend fun transformArtworkUrl(originalUrl: String): Pair<String, Map<String, String>> {
    val config = getArtworkConfig()

    // No config - return original URL with no headers
    if (config == null || config.artworkConfig == null) {
      return originalUrl to emptyMap()
    }

    return try {
      val artworkConfig = config.artworkConfig

      // Create base config with original URL as path
      val baseConfig =
        config.baseConfig ?: RequestConfig(null, null, null, null, null, null, null, null)

      val urlRequestConfig = RequestConfig(null, originalUrl, null, null, null, null, null, null)
      val mergedBaseConfig = RequestConfigBuilder.mergeConfig(baseConfig, urlRequestConfig)

      // Apply artwork transformation (handles transform callback internally)
      val finalConfig = RequestConfigBuilder.mergeConfig(mergedBaseConfig, artworkConfig)

      // Build final URL
      val finalUrl = RequestConfigBuilder.buildUrl(RequestConfigBuilder.toRequestConfig(finalConfig))

      // Extract headers
      val headers = finalConfig.headers?.toMap() ?: emptyMap()

      finalUrl to headers
    } catch (e: Exception) {
      Timber.e(e, "Failed to transform artwork URL: $originalUrl")
      originalUrl to emptyMap()
    }
  }

  /**
   * Transforms an artwork URL for a specific track, supporting the resolve() callback.
   *
   * This is used when pre-transforming artwork URLs before passing tracks to Media3,
   * which is necessary because Media3 doesn't support custom headers for artwork.
   *
   * Note: Headers are NOT applied when using this method since the resulting URL
   * will be loaded by Media3's internal image loader (or Android Auto) which don't
   * support custom headers. Use query parameters for authentication instead.
   *
   * @param track The track whose artwork URL should be transformed
   * @param perRouteConfig Optional per-route artwork config that overrides global config
   * @return The transformed artwork URL, or null if resolve returns undefined or track has no artwork and no resolve callback
   */
  suspend fun transformArtworkUrlForTrack(track: Track, perRouteConfig: MediaRequestConfig? = null): String? {
    val globalConfig = getArtworkConfig()

    // Determine effective artwork config: per-route overrides global
    val effectiveArtworkConfig = perRouteConfig ?: globalConfig?.artworkConfig

    // If no artwork config and no track.artwork, nothing to transform
    if (effectiveArtworkConfig == null && track.artwork == null) {
      return null
    }

    // If no artwork config, just return the original artwork URL
    if (effectiveArtworkConfig == null) {
      return track.artwork
    }

    return try {
      // Create base config
      val baseConfig = globalConfig?.baseConfig ?: RequestConfig(null, null, null, null, null, null, null, null)

      // Start with base config, using track.artwork as the default path if present
      var mergedConfig = if (track.artwork != null) {
        val urlRequestConfig = RequestConfig(null, track.artwork, null, null, null, null, null, null)
        RequestConfigBuilder.mergeConfig(baseConfig, urlRequestConfig)
      } else {
        baseConfig
      }

      // If there's a resolve callback, call it to get per-track config
      // The resolve callback receives the track and can return:
      // - RequestConfig with path/query/etc for URL generation
      // - undefined to indicate no artwork
      val resolvedConfig = effectiveArtworkConfig.resolve?.invoke(track)?.await()?.await()

      // If resolve callback exists and returned null, that means no artwork
      if (effectiveArtworkConfig.resolve != null && resolvedConfig == null) {
        return null
      }

      // Apply artwork base config (static fields only, not resolve)
      val artworkStaticConfig =
        RequestConfig(
          effectiveArtworkConfig.method,
          effectiveArtworkConfig.path,
          effectiveArtworkConfig.baseUrl,
          effectiveArtworkConfig.headers,
          effectiveArtworkConfig.query,
          effectiveArtworkConfig.body,
          effectiveArtworkConfig.contentType,
          effectiveArtworkConfig.userAgent,
        )
      mergedConfig = RequestConfigBuilder.mergeConfig(mergedConfig, artworkStaticConfig)

      // Apply resolved per-track config if present
      if (resolvedConfig != null) {
        mergedConfig = RequestConfigBuilder.mergeConfig(mergedConfig, resolvedConfig)
      }

      // Apply transform callback if present
      val transformedConfig =
        if (effectiveArtworkConfig.transform != null) {
          effectiveArtworkConfig.transform.invoke(mergedConfig, null)?.await()?.await() ?: mergedConfig
        } else {
          mergedConfig
        }

      // Build final URL
      RequestConfigBuilder.buildUrl(transformedConfig)
    } catch (e: Exception) {
      Timber.e(e, "Failed to transform artwork URL for track: ${track.title}")
      // On error, return null to clear artwork and avoid broken images
      null
    }
  }

  /**
   * Blocking version of [transformArtworkUrlForTrack] for use in synchronous contexts.
   */
  fun transformArtworkUrlForTrackBlocking(track: Track, perRouteConfig: MediaRequestConfig? = null): String? {
    return runBlocking { transformArtworkUrlForTrack(track, perRouteConfig) }
  }
}
