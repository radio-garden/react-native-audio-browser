package com.audiobrowser.http

import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.URLEncoder

class RequestConfigBuilder {

  suspend fun buildHttpRequest(
    config: RequestConfig
  ): HttpClient.HttpRequest = withContext(Dispatchers.Default) {

    // Build final URL with query parameters
    val url = buildUrl(config)

    HttpClient.HttpRequest(
      url = url,
      method = config.method?.name ?: "GET",
      headers = config.headers,
      body = config.body,
      contentType = config.contentType ?: HttpClient.DEFAULT_CONTENT_TYPE,
      userAgent = config.userAgent ?: HttpClient.DEFAULT_USER_AGENT
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
      userAgent = override.userAgent ?: base.userAgent
    )
  }

  suspend fun mergeConfig(base: RequestConfig, override: TransformableRequestConfig): RequestConfig {
    return mergeConfig(base, override, emptyMap())
  }

  suspend fun mergeConfig(
    base: RequestConfig, 
    override: TransformableRequestConfig, 
    routeParams: Map<String, String>? = null
  ): RequestConfig {
    // Apply transform function if provided
    return override.transform?.let { transformFn ->
      try {
        transformFn.invoke(base, routeParams).await().await() // Transform result wins completely
      } catch (e: Exception) {
        Timber.e(e, "Failed to apply transform function, using base config")
        base
      }
    } ?: mergeConfig(base, convertToRequestConfig(override)) // Only merge if no transform
  }

  private fun convertToRequestConfig(transformableConfig: TransformableRequestConfig): RequestConfig {
    return RequestConfig(
      path = transformableConfig.path,
      method = transformableConfig.method,
      baseUrl = transformableConfig.baseUrl,
      headers = transformableConfig.headers,
      query = transformableConfig.query,
      body = transformableConfig.body,
      contentType = transformableConfig.contentType,
      userAgent = transformableConfig.userAgent
    )
  }

  private fun mergeHeaders(base: Map<String, String>?, override: Map<String, String>?): Map<String, String>? {
    return when {
      base == null -> override
      override == null -> base
      else -> base + override // Override wins for duplicate keys
    }
  }

  private fun mergeQuery(base: Map<String, String>?, override: Map<String, String>?): Map<String, String>? {
    return when {
      base == null -> override
      override == null -> base
      else -> base + override // Override wins for duplicate keys
    }
  }

  private fun buildUrl(config: RequestConfig): String {
    val baseUrl = config.baseUrl?.trimEnd('/') ?: ""
    val path = config.path?.let {
      if (it.startsWith("/")) it else "/$it"
    } ?: ""

    val queryString = config.query?.let { query ->
      if (query.isNotEmpty()) {
        "?" + query.entries.joinToString("&") { (key, value) ->
          "${encodeUrlParam(key)}=${encodeUrlParam(value)}"
        }
      } else ""
    } ?: ""

    return "$baseUrl$path$queryString"
  }

  private fun encodeUrlParam(param: String): String {
    return URLEncoder.encode(param, "UTF-8")
  }
}