package com.audiobrowser.http

import com.audiobrowser.util.BrowserPathHelper
import com.margelo.nitro.audiobrowser.ArtworkRequestConfig
import com.margelo.nitro.audiobrowser.ImageContext
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.MediaTransformParams
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object RequestConfigBuilder {

  suspend fun buildHttpRequest(config: RequestConfig): HttpClient.HttpRequest =
    withContext(Dispatchers.Default) {

      // Build final URL with query parameters
      val url = buildUrl(config)

      HttpClient.HttpRequest(
        url = url,
        method = config.method?.name ?: "GET",
        headers = config.headers,
        body = config.body,
        contentType = config.contentType ?: HttpClient.DEFAULT_CONTENT_TYPE,
        userAgent = config.userAgent ?: HttpClient.DEFAULT_USER_AGENT,
      )
    }

  fun mergeConfig(base: RequestConfig, override: RequestConfig): RequestConfig {
    return RequestConfig(
      path = override.path ?: base.path,
      method = override.method ?: base.method,
      baseUrl = override.baseUrl ?: base.baseUrl,
      headers = mergeHeaders(base.headers, override.headers),
      query = mergeQuery(base.query, override.query),
      body = override.body ?: base.body,
      contentType = override.contentType ?: base.contentType,
      userAgent = override.userAgent ?: base.userAgent,
    )
  }

  suspend fun mergeConfig(
    base: RequestConfig,
    override: TransformableRequestConfig,
  ): RequestConfig {
    return mergeConfig(base, override, emptyMap())
  }

  suspend fun mergeConfig(
    base: RequestConfig,
    override: TransformableRequestConfig,
    routeParams: Map<String, String>? = null,
  ): RequestConfig {
    // Apply transform function if provided
    return override.transform?.let { transformFn ->
      try {
        transformFn.invoke(base, routeParams).await().await() // Transform result wins completely
      } catch (e: Exception) {
        Timber.e(e, "Failed to apply transform function, using base config")
        base
      }
    } ?: mergeConfig(base, toRequestConfig(override)) // Only merge if no transform
  }

  suspend fun mergeConfig(
    base: RequestConfig,
    override: MediaRequestConfig,
    routeParams: Map<String, String>? = null,
  ): MediaRequestConfig {
    // Apply transform function if provided - transform result wins completely
    val finalConfig: RequestConfig =
      override.transform?.let { transformFn ->
        try {
          Timber.d("Invoking media transform for URL: ${base.path}")
          val transformed = transformFn.invoke(base, routeParams).await().await()
          Timber.d(
            "Media transform result: path=${transformed.path}, baseUrl=${transformed.baseUrl}, headers=${transformed.headers}, userAgent=${transformed.userAgent}"
          )
          transformed
        } catch (e: Exception) {
          Timber.e(e, "Failed to apply media transform function, using base config")
          base
        }
      }
        ?: RequestConfig(
          path = override.path ?: base.path,
          method = override.method ?: base.method,
          baseUrl = override.baseUrl ?: base.baseUrl,
          headers = mergeHeaders(base.headers, override.headers),
          query = mergeQuery(base.query, override.query),
          body = override.body ?: base.body,
          contentType = override.contentType ?: base.contentType,
          userAgent = override.userAgent ?: base.userAgent,
        )

    // Wrap the final RequestConfig in MediaRequestConfig to preserve resolve callback
    return MediaRequestConfig(
      resolve = override.resolve,
      transform = override.transform,
      method = finalConfig.method,
      path = finalConfig.path,
      baseUrl = finalConfig.baseUrl,
      headers = finalConfig.headers,
      query = finalConfig.query,
      body = finalConfig.body,
      contentType = finalConfig.contentType,
      userAgent = finalConfig.userAgent,
    )
  }

  /**
   * Merges artwork request config with base config, applying transform with ImageContext.
   * Used for artwork URL transformation where size hints are needed.
   */
  suspend fun mergeConfig(
    base: RequestConfig,
    override: ArtworkRequestConfig,
    imageContext: ImageContext? = null,
  ): ArtworkRequestConfig {
    // Apply transform function if provided - transform result wins completely
    val finalConfig: RequestConfig =
      override.transform?.let { transformFn ->
        try {
          Timber.d("Invoking artwork transform for URL: ${base.path}")
          val transformed = transformFn.invoke(MediaTransformParams(base, imageContext)).await().await()
          Timber.d(
            "Artwork transform result: path=${transformed.path}, baseUrl=${transformed.baseUrl}, headers=${transformed.headers}, userAgent=${transformed.userAgent}"
          )
          transformed
        } catch (e: Exception) {
          Timber.e(e, "Failed to apply artwork transform function, using base config")
          base
        }
      }
        ?: RequestConfig(
          path = override.path ?: base.path,
          method = override.method ?: base.method,
          baseUrl = override.baseUrl ?: base.baseUrl,
          headers = mergeHeaders(base.headers, override.headers),
          query = mergeQuery(base.query, override.query),
          body = override.body ?: base.body,
          contentType = override.contentType ?: base.contentType,
          userAgent = override.userAgent ?: base.userAgent,
        )

    // Wrap the final RequestConfig in ArtworkRequestConfig to preserve callbacks and query params
    return ArtworkRequestConfig(
      resolve = override.resolve,
      transform = override.transform,
      imageQueryParams = override.imageQueryParams,
      method = finalConfig.method,
      path = finalConfig.path,
      baseUrl = finalConfig.baseUrl,
      headers = finalConfig.headers,
      query = finalConfig.query,
      body = finalConfig.body,
      contentType = finalConfig.contentType,
      userAgent = finalConfig.userAgent,
    )
  }

  fun toRequestConfig(artworkConfig: ArtworkRequestConfig): RequestConfig {
    return RequestConfig(
      path = artworkConfig.path,
      method = artworkConfig.method,
      baseUrl = artworkConfig.baseUrl,
      headers = artworkConfig.headers,
      query = artworkConfig.query,
      body = artworkConfig.body,
      contentType = artworkConfig.contentType,
      userAgent = artworkConfig.userAgent,
    )
  }

  fun toRequestConfig(transformableConfig: TransformableRequestConfig): RequestConfig {
    return RequestConfig(
      path = transformableConfig.path,
      method = transformableConfig.method,
      baseUrl = transformableConfig.baseUrl,
      headers = transformableConfig.headers,
      query = transformableConfig.query,
      body = transformableConfig.body,
      contentType = transformableConfig.contentType,
      userAgent = transformableConfig.userAgent,
    )
  }

  fun toRequestConfig(mediaConfig: MediaRequestConfig): RequestConfig {
    return RequestConfig(
      path = mediaConfig.path,
      method = mediaConfig.method,
      baseUrl = mediaConfig.baseUrl,
      headers = mediaConfig.headers,
      query = mediaConfig.query,
      body = mediaConfig.body,
      contentType = mediaConfig.contentType,
      userAgent = mediaConfig.userAgent,
    )
  }

  private fun mergeHeaders(
    base: Map<String, String>?,
    override: Map<String, String>?,
  ): Map<String, String>? {
    return when {
      base == null -> override
      override == null -> base
      else -> base + override // Override wins for duplicate keys
    }
  }

  private fun mergeQuery(
    base: Map<String, String>?,
    override: Map<String, String>?,
  ): Map<String, String>? {
    return when {
      base == null -> override
      override == null -> base
      else -> base + override // Override wins for duplicate keys
    }
  }

  internal fun buildUrl(config: RequestConfig): String {
    val path = config.path ?: ""
    val baseUrl = config.baseUrl

    // Use BrowserPathHelper for consistent URL building
    val url = BrowserPathHelper.buildUrl(baseUrl, path)

    // Add query parameters if any
    val queryString =
      config.query?.let { query ->
        if (query.isNotEmpty()) {
          "?" +
            query.entries.joinToString("&") { (key, value) ->
              "${encodeUrlParam(key)}=${encodeUrlParam(value)}"
            }
        } else ""
      } ?: ""

    return "$url$queryString"
  }

  private fun encodeUrlParam(param: String): String {
    return URLEncoder.encode(param, "UTF-8")
  }
}
