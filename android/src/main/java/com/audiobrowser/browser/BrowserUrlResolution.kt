package com.audiobrowser.browser

import com.audiobrowser.http.RequestConfigBuilder
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.RequestConfig

/**
 * Outbound URL resolution for media (and artwork) — the Android analog of iOS
 * `BrowserManager+URLResolution.swift`. Owns how a Track's `src` becomes a
 * fetchable request: request layer (shared, incl. its transform) → media config →
 * per-Track `media.resolve`, with transform-wins semantics centralised in
 * [RequestConfigBuilder].
 */

/**
 * Builds the media request config for [originalUrl]. Returns null only when
 * neither a request layer nor a media config is set (the caller then uses the
 * original URL as-is). Mirrors iOS `resolveMediaUrl`.
 */
suspend fun BrowserManager.resolveMediaUrl(originalUrl: String): MediaRequestConfig? {
  val mediaConfig = config.media
  // The request layer counts as present when a static `request` OR a `requestResolver`
  // is set — a resolver-only consumer still needs its baseUrl/headers/transform
  // applied to media URLs.
  val hasRequestLayer = config.request != null || config.requestResolver != null
  if (mediaConfig == null && !hasRequestLayer) return null

  // Layered: request (shared, incl. its transform) → media. The request layer runs
  // for media even when no media-specific config is present (so a relative src
  // still gets baseUrl).
  val requestConfig = resolvedRequestConfig()
  var base =
    RequestConfig(
      method = null,
      path = originalUrl,
      baseUrl = null,
      headers = null,
      query = null,
      body = null,
      contentType = null,
      userAgent = null,
    )
  requestConfig?.let { base = RequestConfigBuilder.mergeConfig(base, it) }
  val mediaLayered =
    if (mediaConfig != null) {
      RequestConfigBuilder.mergeConfig(base, mediaConfig)
    } else {
      MediaRequestConfig(
        resolve = null,
        resolveSync = null,
        transform = null,
        transformSync = null,
        method = base.method,
        path = base.path,
        baseUrl = base.baseUrl,
        headers = base.headers,
        query = base.query,
        body = base.body,
        contentType = base.contentType,
        userAgent = base.userAgent,
      )
    }
  // Final, most-specific layer: media.resolve(track). The cached Track carries any
  // per-track `request` override (e.g. a strict-UA sentinel); resolve reads it and
  // returns the winning config. Only look up the track when a resolve callback
  // exists, to avoid a needless cache lookup (and its miss-log) otherwise — async
  // OR sync (the old code checked only `resolve`, silently breaking
  // resolveSync-only consumers).
  val track =
    if (mediaConfig?.resolve != null || mediaConfig?.resolveSync != null) {
      getCachedTrack(originalUrl)
    } else null
  return RequestConfigBuilder.applyMediaResolve(mediaLayered, track)
}
