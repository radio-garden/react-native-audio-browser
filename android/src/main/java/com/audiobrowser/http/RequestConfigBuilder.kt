package com.audiobrowser.http

import com.audiobrowser.util.BrowserPathHelper
import com.margelo.nitro.audiobrowser.ArtworkRequestConfig
import com.margelo.nitro.audiobrowser.ImageContext
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.MediaTransformParams
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import com.margelo.nitro.core.Promise
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

  /**
   * Run-both resolve composition: the async-resolved config first, then the sync-resolved merged
   * over it (sync winning). Returns null when neither produced a config. Extracted so the merge
   * ordering is unit-testable without Nitro callbacks; used by [applyMediaResolve] and the artwork
   * resolve in CoilBitmapLoader.
   */
  fun composeResolved(asyncResolved: RequestConfig?, syncResolved: RequestConfig?): RequestConfig? {
    return when {
      asyncResolved == null -> syncResolved
      syncResolved == null -> asyncResolved
      else -> mergeConfig(asyncResolved, syncResolved)
    }
  }

  /**
   * Awaits an **async** config callback. It lowers to `Promise<Promise<RequestConfig>>` (bridge hop
   * → JS promise), so it is a DOUBLE await. That depth is the bug-prone part (single-awaiting an
   * async callback hands a `Promise` downstream — the original "empty config" bug), so it lives in
   * exactly one place. Pairs with [awaitSyncConfig].
   */
  suspend fun awaitAsyncConfig(promise: Promise<Promise<RequestConfig>>): RequestConfig =
    promise.await().await()

  /** Awaits a **sync** config callback (`Promise<RequestConfig>` — a single await). */
  suspend fun awaitSyncConfig(promise: Promise<RequestConfig>): RequestConfig = promise.await()

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
    // A transform (async and/or sync) wins completely; with both set they run as a
    // pipeline — async first, then sync. Otherwise merge the static fields.
    if (override.transform == null && override.transformSync == null) {
      return mergeConfig(base, toRequestConfig(override))
    }
    return try {
      // Async first, then sync (each replaces the running config). The bridge await
      // depth is centralised in awaitAsync/SyncConfig.
      var result = base
      override.transform?.let { result = awaitAsyncConfig(it.invoke(result, routeParams)) }
      override.transformSync?.let { result = awaitSyncConfig(it.invoke(result, routeParams)) }
      result
    } catch (e: Exception) {
      Timber.e(e, "Failed to apply transform function, using base config")
      base
    }
  }

  suspend fun mergeConfig(
    base: RequestConfig,
    override: MediaRequestConfig,
    routeParams: Map<String, String>? = null,
  ): MediaRequestConfig {
    // A transform (async and/or sync) wins completely; with both set they run as a
    // pipeline — async first, then sync. Otherwise merge the static fields.
    val finalConfig: RequestConfig =
      if (override.transform != null || override.transformSync != null) {
        try {
          var result = base
          override.transform?.let { result = awaitAsyncConfig(it.invoke(result, routeParams)) }
          override.transformSync?.let { result = awaitSyncConfig(it.invoke(result, routeParams)) }
          result
        } catch (e: Exception) {
          Timber.e(e, "Failed to apply media transform function, using base config")
          base
        }
      } else {
        RequestConfig(
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

    // Wrap the final RequestConfig in MediaRequestConfig to preserve callbacks
    return MediaRequestConfig(
      resolve = override.resolve,
      resolveSync = override.resolveSync,
      transform = override.transform,
      transformSync = override.transformSync,
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
   * Applies the per-track `media.resolve(track)` callback as the final, most-specific layer over an
   * already request+media-layered config. The callback receives the full Track — carrying any
   * per-track `request` override (e.g. a strict-UA sentinel the consumer swaps for a clean
   * User-Agent) — and returns the config whose fields win.
   *
   * No-op when the media config has no `resolve` callback or no track is available (so the
   * request/media layers stand). The callback may return its config synchronously (variant `first`)
   * or via a Promise (`second`). Mirrors iOS resolveMediaTrackConfig + applyMediaResolveLayer.
   */
  suspend fun applyMediaResolve(layered: MediaRequestConfig, track: Track?): MediaRequestConfig {
    if (track == null) return layered
    if (layered.resolve == null && layered.resolveSync == null) return layered
    val resolved: RequestConfig =
      try {
        // Async first, then sync (merged, sync winning) — via the tested helper.
        val asyncResolved = layered.resolve?.let { awaitAsyncConfig(it.invoke(track)) }
        val syncResolved = layered.resolveSync?.let { awaitSyncConfig(it.invoke(track)) }
        composeResolved(asyncResolved, syncResolved) ?: return layered
      } catch (e: Exception) {
        Timber.e(e, "Failed to apply media.resolve, using layered config")
        return layered
      }
    // Resolve wins: merge it over the layered config (override-wins on every field).
    val merged = mergeConfig(toRequestConfig(layered), resolved)
    return MediaRequestConfig(
      resolve = layered.resolve,
      resolveSync = layered.resolveSync,
      transform = layered.transform,
      transformSync = layered.transformSync,
      method = merged.method,
      path = merged.path,
      baseUrl = merged.baseUrl,
      headers = merged.headers,
      query = merged.query,
      body = merged.body,
      contentType = merged.contentType,
      userAgent = merged.userAgent,
    )
  }

  /**
   * Merges artwork request config with base config, applying transform with ImageContext. Used for
   * artwork URL transformation where size hints are needed.
   */
  suspend fun mergeConfig(
    base: RequestConfig,
    override: ArtworkRequestConfig,
    imageContext: ImageContext? = null,
  ): ArtworkRequestConfig {
    // A transform (async and/or sync) wins completely; with both set they run as a
    // pipeline — async first, then sync. Otherwise merge the static fields.
    val finalConfig: RequestConfig =
      if (override.transform != null || override.transformSync != null) {
        try {
          var result = base
          override.transform?.let {
            result = awaitAsyncConfig(it.invoke(MediaTransformParams(result, imageContext)))
          }
          override.transformSync?.let {
            result = awaitSyncConfig(it.invoke(MediaTransformParams(result, imageContext)))
          }
          result
        } catch (e: Exception) {
          Timber.e(e, "Failed to apply artwork transform function, using base config")
          base
        }
      } else {
        RequestConfig(
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

    // Wrap the final RequestConfig in ArtworkRequestConfig to preserve callbacks and query params
    return ArtworkRequestConfig(
      resolve = override.resolve,
      resolveSync = override.resolveSync,
      transform = override.transform,
      transformSync = override.transformSync,
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
