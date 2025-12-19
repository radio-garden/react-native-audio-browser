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
  private val getArtworkConfig: () -> ArtworkConfig?,
  private val getArtworkSizeHint: () -> Int? = { null },
) : BitmapLoader {

  private val scope = CoroutineScope(Dispatchers.IO)

  /** Configuration for artwork requests including headers and URL transformation. */
  data class ArtworkConfig(val baseConfig: RequestConfig?, val artworkConfig: ArtworkRequestConfig?)

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
        val sizeHint = getArtworkSizeHint()

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
          requestBuilder.decoderFactory { result, options, _ ->
            SvgDecoder(result.source, options)
          }
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

      // Create base config with original URL as path
      val baseConfig =
        config.baseConfig ?: RequestConfig(null, null, null, null, null, null, null, null)

      val urlRequestConfig = RequestConfig(null, originalUrl, null, null, null, null, null, null)
      var mergedBaseConfig = RequestConfigBuilder.mergeConfig(baseConfig, urlRequestConfig)

      // Create ImageContext from size hint if available
      val imageContext = sizeHintPixels?.takeIf { it > 0 }?.let {
        ImageContext(it.toDouble(), it.toDouble())
      }

      // Apply image query params BEFORE transform (so transform can override)
      val queryParams = artworkConfig.imageQueryParams
      if (imageContext != null && queryParams != null) {
        val contextQuery = mutableMapOf<String, String>()
        queryParams.width?.let { key -> imageContext.width?.let { contextQuery[key] = it.toInt().toString() } }
        queryParams.height?.let { key -> imageContext.height?.let { contextQuery[key] = it.toInt().toString() } }

        if (contextQuery.isNotEmpty()) {
          Timber.d("Adding image query params: $contextQuery")
          val existingQuery = mergedBaseConfig.query?.toMutableMap() ?: mutableMapOf()
          existingQuery.putAll(contextQuery)
          mergedBaseConfig = mergedBaseConfig.copy(query = existingQuery)
        }
      }

      // Apply artwork transformation (transform can override imageQueryParams)
      val finalConfig = RequestConfigBuilder.mergeConfig(mergedBaseConfig, artworkConfig, imageContext)

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

  /**
   * Transforms an artwork URL for a specific track, returning a complete ImageSource.
   *
   * Returns an ImageSource containing:
   * - uri: Transformed URL with query parameters for authentication
   * - method: HTTP method (if configured)
   * - headers: Merged headers including User-Agent and Content-Type
   * - body: Request body (if configured)
   *
   * @param track The track whose artwork URL should be transformed
   * @param perRouteConfig Optional per-route artwork config that overrides global config
   * @param imageContext Optional size context for CDN URL generation (null at browse-time)
   * @return ImageSource ready for React Native's Image component, or null if no artwork
   */
  suspend fun transformArtworkUrlForTrack(
    track: Track,
    perRouteConfig: ArtworkRequestConfig? = null,
    imageContext: ImageContext? = null,
  ): ImageSource? {
    val globalConfig = getArtworkConfig()

    // Determine effective artwork config: per-route overrides global
    val effectiveArtworkConfig = perRouteConfig ?: globalConfig?.artworkConfig

    // Treat empty string as null for artwork
    val trackArtwork = track.artwork?.takeIf { it.isNotEmpty() }

    Timber.d("transformArtworkUrlForTrack: track='${track.title}', artwork='$trackArtwork', hasConfig=${effectiveArtworkConfig != null}")

    // If no artwork config and no track.artwork, nothing to transform
    if (effectiveArtworkConfig == null && trackArtwork == null) {
      Timber.d("transformArtworkUrlForTrack: No config and no artwork, returning null")
      return null
    }

    // If no artwork config, just return the original artwork URL as a simple ImageSource
    if (effectiveArtworkConfig == null) {
      return trackArtwork?.let {
        Timber.d("transformArtworkUrlForTrack: No config, returning original artwork: $it")
        ImageSource(uri = it, method = null, headers = null, body = null)
      }
    }

    return try {
      // Create base config
      val baseConfig =
        globalConfig?.baseConfig ?: RequestConfig(null, null, null, null, null, null, null, null)

      // Start with base config, using track.artwork as the default path if present
      var mergedConfig =
        if (trackArtwork != null) {
          val urlRequestConfig =
            RequestConfig(null, trackArtwork, null, null, null, null, null, null)
          RequestConfigBuilder.mergeConfig(baseConfig, urlRequestConfig)
        } else {
          baseConfig
        }

      // If there's a resolve callback, call it to get per-track config
      // The resolve callback receives the track directly and can return:
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

      // Apply image query params BEFORE transform (so transform can override)
      if (imageContext != null) {
        val queryParams = effectiveArtworkConfig.imageQueryParams
        if (queryParams != null) {
          val contextQuery = mutableMapOf<String, String>()
          queryParams.width?.let { key -> imageContext.width?.let { contextQuery[key] = it.toInt().toString() } }
          queryParams.height?.let { key -> imageContext.height?.let { contextQuery[key] = it.toInt().toString() } }

          if (contextQuery.isNotEmpty()) {
            Timber.d("Adding image query params: $contextQuery")
            val existingQuery = mergedConfig.query?.toMutableMap() ?: mutableMapOf()
            existingQuery.putAll(contextQuery)
            mergedConfig = mergedConfig.copy(query = existingQuery)
          }
        }
      }

      // Apply transform callback if present (can override imageQueryParams)
      val transformedConfig =
        if (effectiveArtworkConfig.transform != null) {
          effectiveArtworkConfig.transform.invoke(MediaTransformParams(mergedConfig, imageContext))?.await()?.await()
            ?: mergedConfig
        } else {
          mergedConfig
        }

      // Build final URL
      val uri = RequestConfigBuilder.buildUrl(transformedConfig)

      // If URI is empty, there's no valid artwork path
      if (uri.isEmpty()) {
        Timber.d("transformArtworkUrlForTrack: Built URI is empty, returning null")
        return null
      }

      Timber.d("transformArtworkUrlForTrack: Built URI: $uri")

      // Build headers map, merging explicit headers with userAgent and contentType
      val headers =
        buildHeadersMap(
          transformedConfig.headers?.toMap(),
          transformedConfig.userAgent,
          transformedConfig.contentType,
        )

      ImageSource(
        uri = uri,
        method = transformedConfig.method,
        headers = headers,
        body = transformedConfig.body,
      )
    } catch (e: Exception) {
      Timber.e(e, "Failed to transform artwork URL for track: ${track.title}")
      // On error, return null to clear artwork and avoid broken images
      null
    }
  }

  /** Builds a headers map, merging explicit headers with userAgent and contentType. */
  private fun buildHeadersMap(
    headers: Map<String, String>?,
    userAgent: String?,
    contentType: String?,
  ): Map<String, String>? {
    val mergedHeaders = mutableMapOf<String, String>()

    // Add explicit headers
    headers?.let { mergedHeaders.putAll(it) }

    // Add User-Agent if present and not already set
    if (userAgent != null && !mergedHeaders.containsKey("User-Agent")) {
      mergedHeaders["User-Agent"] = userAgent
    }

    // Add Content-Type if present and not already set
    if (contentType != null && !mergedHeaders.containsKey("Content-Type")) {
      mergedHeaders["Content-Type"] = contentType
    }

    return mergedHeaders.ifEmpty { null }
  }

  /** Blocking version of [transformArtworkUrlForTrack] for use in synchronous contexts. */
  fun transformArtworkUrlForTrackBlocking(
    track: Track,
    perRouteConfig: ArtworkRequestConfig? = null,
    imageContext: ImageContext? = null,
  ): ImageSource? {
    return runBlocking { transformArtworkUrlForTrack(track, perRouteConfig, imageContext) }
  }
}
