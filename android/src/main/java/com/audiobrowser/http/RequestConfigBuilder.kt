package com.audiobrowser.http

import com.audiobrowser.util.BrowserPathHelper
import com.audiobrowser.util.awaitCancellable
import com.audiobrowser.util.awaitNested
import com.margelo.nitro.audiobrowser.ArtworkRequestConfig
import com.margelo.nitro.audiobrowser.MediaRequestConfig
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

  /**
   * Every non-null field of [override] wins, `path` included; headers and query merge key by key.
   */
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

  /** Merges the sync-resolved config over the async-resolved one; null when both are null. */
  fun composeResolved(asyncResolved: RequestConfig?, syncResolved: RequestConfig?): RequestConfig? {
    return when {
      asyncResolved == null -> syncResolved
      syncResolved == null -> asyncResolved
      else -> mergeConfig(asyncResolved, syncResolved)
    }
  }

  /**
   * An async config callback lowers to `Promise<Promise<RequestConfig>>` (the bridge hop, then the
   * JS promise); awaiting it once hands a Promise downstream as the config.
   */
  suspend fun awaitAsyncConfig(promise: Promise<Promise<RequestConfig>>): RequestConfig =
    promise.awaitNested()

  suspend fun awaitSyncConfig(promise: Promise<RequestConfig>): RequestConfig =
    promise.awaitCancellable()

  private fun MediaRequestConfig.withRequestFields(c: RequestConfig) =
    MediaRequestConfig(
      resolve = resolve,
      resolveSync = resolveSync,
      transform = transform,
      transformSync = transformSync,
      method = c.method,
      path = c.path,
      baseUrl = c.baseUrl,
      headers = c.headers,
      query = c.query,
      body = c.body,
      contentType = c.contentType,
      userAgent = c.userAgent,
    )

  suspend fun mergeConfig(
    base: RequestConfig,
    override: TransformableRequestConfig,
  ): RequestConfig {
    return mergeConfig(base, override, emptyMap())
  }

  /**
   * Applies [override] as a Request-Config Layer (GLOSSARY.md): its transforms, async then sync,
   * replace the config and its static fields are then ignored; without one, its static fields merge
   * over [base] except `path`, which stays the base's. Mirrors iOS `applyLayer`. A transform that
   * throws yields [base].
   */
  suspend fun mergeConfig(
    base: RequestConfig,
    override: TransformableRequestConfig,
    routeParams: Map<String, String>? = null,
  ): RequestConfig {
    if (override.transform == null && override.transformSync == null) {
      return mergeConfig(base, toRequestConfig(override)).copy(path = base.path)
    }
    return try {
      var result = base
      override.transform?.let { result = awaitAsyncConfig(it.invoke(result, routeParams)) }
      override.transformSync?.let { result = awaitSyncConfig(it.invoke(result, routeParams)) }
      result
    } catch (e: Exception) {
      Timber.e(e, "Failed to apply transform function, using base config")
      base
    }
  }

  /**
   * The layer rule of the [TransformableRequestConfig] overload, keeping [override]'s resolve
   * callbacks.
   */
  suspend fun mergeConfig(
    base: RequestConfig,
    override: MediaRequestConfig,
    routeParams: Map<String, String>? = null,
  ): MediaRequestConfig =
    override.withRequestFields(mergeConfig(base, override.asTransformable(), routeParams))

  private fun MediaRequestConfig.asTransformable() =
    TransformableRequestConfig(
      transform = transform,
      transformSync = transformSync,
      method = method,
      path = path,
      baseUrl = baseUrl,
      headers = headers,
      query = query,
      body = body,
      contentType = contentType,
      userAgent = userAgent,
    )

  /**
   * Merges the config `media.resolve(track)` and `resolveSync(track)` return over [layered], as the
   * last layer: its non-null fields win, `path` included. Returns [layered] unchanged when there is
   * no resolve callback or it throws. Mirrors iOS `resolveMediaTrackConfig`.
   */
  suspend fun applyMediaResolve(layered: MediaRequestConfig, track: Track): MediaRequestConfig {
    if (layered.resolve == null && layered.resolveSync == null) return layered
    val resolved: RequestConfig =
      try {
        val asyncResolved = layered.resolve?.let { awaitAsyncConfig(it.invoke(track)) }
        val syncResolved = layered.resolveSync?.let { awaitSyncConfig(it.invoke(track)) }
        composeResolved(asyncResolved, syncResolved) ?: return layered
      } catch (e: Exception) {
        Timber.e(e, "Failed to apply media.resolve, using layered config")
        return layered
      }
    val merged = mergeConfig(toRequestConfig(layered), resolved)
    return layered.withRequestFields(merged)
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
      else -> base + override
    }
  }

  private fun mergeQuery(
    base: Map<String, String>?,
    override: Map<String, String>?,
  ): Map<String, String>? {
    return when {
      base == null -> override
      override == null -> base
      else -> base + override
    }
  }

  internal fun buildUrl(config: RequestConfig): String {
    val path = config.path ?: ""
    val baseUrl = config.baseUrl

    val url = BrowserPathHelper.buildUrl(baseUrl, path)

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
