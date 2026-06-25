package com.audiobrowser.browser

import com.audiobrowser.http.RequestConfigBuilder
import com.margelo.nitro.audiobrowser.ArtworkRequestConfig
import com.margelo.nitro.audiobrowser.ImageContext
import com.margelo.nitro.audiobrowser.ImageQueryParams
import com.margelo.nitro.audiobrowser.ImageSource
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.MediaResolveTarget
import com.margelo.nitro.audiobrowser.MediaTransformParams
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.Track
import timber.log.Timber

/**
 * Outbound URL resolution for media and artwork — the Android analog of iOS
 * `BrowserManager+URLResolution.swift`. Owns how a Track's `src` / `artwork` becomes a fetchable
 * request: request layer (shared, incl. its transform) → kind config (media / artwork /
 * nowPlayingArtwork) → per-Track Resolve, with transform-wins semantics centralised in
 * [RequestConfigBuilder].
 */

/**
 * Builds the media request config for [originalUrl]. Returns null only when neither a request layer
 * nor a media config is set (the caller then uses the original URL as-is). Mirrors the web stub's
 * and iOS's `resolveMediaUrl`.
 */
suspend fun BrowserManager.resolveMediaUrl(
  originalUrl: String,
  target: MediaResolveTarget = MediaResolveTarget.LOCAL,
): MediaRequestConfig? {
  val mediaConfig = config.media
  // The request layer counts as present when a static `request` OR a `requestResolver`
  // is set — a resolver-only consumer still needs its baseUrl/headers/transform
  // applied to media URLs.
  val hasRequestLayer = config.request != null || config.requestResolver != null
  if (mediaConfig == null && !hasRequestLayer) return null

  // The app's media transform receives [target] as its 3rd arg (the cross-platform contract), so a
  // target:'cast' resolution lets it branch and emit a self-contained (query-signed),
  // receiver-fetchable URL. routeParams stays emptyMap() for media — exactly what the previous
  // 2-arg mergeConfig calls did — so the LOCAL path is byte-for-byte unchanged apart from the
  // transform now seeing target=LOCAL.
  val routeParams: Map<String, String> = emptyMap()

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
  requestConfig?.let { base = RequestConfigBuilder.mergeConfig(base, it, routeParams, target) }
  val mediaLayered =
    if (mediaConfig != null) {
      RequestConfigBuilder.mergeConfig(base, mediaConfig, routeParams, target)
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

/**
 * Resolves a Track's artwork into a fetchable [ImageSource]: request layer → artwork config
 * (per-route overrides global) → per-Track `artwork.resolve` → image query params from
 * [imageContext] → artwork transform → `{id}` substitution. Mirrors the web stub's and iOS's
 * `resolveArtworkUrl` flow. Returns null when there is no artwork (or a resolver explicitly
 * produced none and the track has no artwork URL).
 */
suspend fun BrowserManager.resolveArtworkUrl(
  track: Track,
  perRouteConfig: ArtworkRequestConfig? = null,
  imageContext: ImageContext? = null,
  target: MediaResolveTarget = MediaResolveTarget.LOCAL,
): ImageSource? {
  val effectiveArtworkConfig = perRouteConfig ?: config.artwork
  // Treat empty string as null for artwork
  val trackArtwork = track.artwork?.takeIf { it.isNotEmpty() }

  // If no artwork config and no track.artwork, nothing to transform
  if (effectiveArtworkConfig == null && trackArtwork == null) return null

  // If no artwork config, just return the original artwork URL as a simple ImageSource
  if (effectiveArtworkConfig == null) {
    return trackArtwork?.let { ImageSource(uri = it, method = null, headers = null, body = null) }
  }

  return try {
    // Base via the shared request layer (its transform runs for artwork too),
    // with the track's artwork as the path when present.
    var mergedConfig =
      RequestConfig(
        method = null,
        path = null,
        baseUrl = null,
        headers = null,
        query = null,
        body = null,
        contentType = null,
        userAgent = null,
      )
    resolvedRequestConfig()?.let {
      mergedConfig = RequestConfigBuilder.mergeConfig(mergedConfig, it, emptyMap())
    }
    if (trackArtwork != null) {
      mergedConfig = mergedConfig.copy(path = trackArtwork)
    }

    // Per-track resolution — async `resolve` first, then `resolveSync` merged over
    // it (sync winning) via the tested helper. Matches the web stub.
    val asyncResolved =
      effectiveArtworkConfig.resolve?.let {
        RequestConfigBuilder.awaitAsyncConfig(it.invoke(track))
      }
    val syncResolved =
      effectiveArtworkConfig.resolveSync?.let {
        RequestConfigBuilder.awaitSyncConfig(it.invoke(track))
      }
    val resolvedConfig = RequestConfigBuilder.composeResolved(asyncResolved, syncResolved)

    // A resolver ran but produced nothing AND there's no artwork URL either → no
    // artwork (matches the web stub; with a track artwork present the static/url
    // flow continues).
    if (
      (effectiveArtworkConfig.resolve != null || effectiveArtworkConfig.resolveSync != null) &&
        resolvedConfig == null &&
        trackArtwork == null
    ) {
      return null
    }

    // Artwork config's static fields (not resolve/transform — those run separately).
    mergedConfig =
      RequestConfigBuilder.mergeConfig(
        mergedConfig,
        RequestConfigBuilder.toRequestConfig(effectiveArtworkConfig),
      )
    resolvedConfig?.let { mergedConfig = RequestConfigBuilder.mergeConfig(mergedConfig, it) }

    // Image query params BEFORE transform (so the transform can override them).
    mergedConfig =
      applyImageQueryParams(mergedConfig, effectiveArtworkConfig.imageQueryParams, imageContext)

    // Transform (async first, then sync), receiving the image context.
    var transformedConfig = mergedConfig
    effectiveArtworkConfig.transform?.let {
      transformedConfig =
        RequestConfigBuilder.awaitAsyncConfig(
          it.invoke(MediaTransformParams(transformedConfig, imageContext, target))
        )
    }
    effectiveArtworkConfig.transformSync?.let {
      transformedConfig =
        RequestConfigBuilder.awaitSyncConfig(
          it.invoke(MediaTransformParams(transformedConfig, imageContext, target))
        )
    }

    // `{id}` token substitution, only for a non-empty id. Mirrors iOS substituteTrackId.
    track.id
      ?.takeIf { it.isNotEmpty() }
      ?.let { transformedConfig = substituteTrackId(transformedConfig, it) }

    val uri = RequestConfigBuilder.buildUrl(transformedConfig)
    // If URI is empty, there's no valid artwork path
    if (uri.isEmpty()) return null

    ImageSource(
      uri = uri,
      method = transformedConfig.method,
      headers =
        buildHeadersMap(
          transformedConfig.headers?.toMap(),
          transformedConfig.userAgent,
          transformedConfig.contentType,
        ),
      body = transformedConfig.body,
    )
  } catch (e: Exception) {
    Timber.e(e, "Failed to transform artwork URL for track: ${track.title}")
    // On error, return null to clear artwork and avoid broken images
    null
  }
}

/**
 * Display-time artwork resolution by URI (Media3's BitmapLoader only receives a URI). A registry
 * hit re-resolves Track-first with the real display-size [imageContext] — never re-transforming an
 * already-transformed URL. Returns null for unknown URIs; the caller decides the fallback.
 */
suspend fun BrowserManager.displayArtworkSource(
  uri: String,
  imageContext: ImageContext?,
): ImageSource? {
  val entry = artworkResolutions.lookup(uri) ?: return null
  return resolveArtworkUrl(entry.track, entry.perRouteConfig, imageContext)
}

/**
 * Header-only fallback for display-time artwork URIs that cannot be attributed to a Track (registry
 * eviction, process-death restore): the URI is fetched as-is — never re-transformed — but the
 * *static* request/artwork headers still apply, so header-authenticated artwork keeps working.
 * Transforms are deliberately not run (no Track, no trustworthy base config); headers added by a
 * transform rather than static config are out of reach here, which is the documented limit. Returns
 * null when there are no headers to add.
 */
suspend fun BrowserManager.unattributedArtworkSource(uri: String): ImageSource? {
  var merged =
    RequestConfig(
      method = null,
      path = null,
      baseUrl = null,
      headers = null,
      query = null,
      body = null,
      contentType = null,
      userAgent = null,
    )
  resolvedRequestConfig()?.let {
    merged = RequestConfigBuilder.mergeConfig(merged, RequestConfigBuilder.toRequestConfig(it))
  }
  config.artwork?.let {
    merged = RequestConfigBuilder.mergeConfig(merged, RequestConfigBuilder.toRequestConfig(it))
  }
  val headers = buildHeadersMap(merged.headers?.toMap(), merged.userAgent, merged.contentType)
  if (headers == null) return null
  return ImageSource(uri = uri, method = null, headers = headers, body = null)
}

/** Folds [imageContext] width/height into the query under the configured param names. */
private fun applyImageQueryParams(
  config: RequestConfig,
  imageQueryParams: ImageQueryParams?,
  imageContext: ImageContext?,
): RequestConfig {
  if (imageContext == null || imageQueryParams == null) return config
  val contextQuery = mutableMapOf<String, String>()
  imageQueryParams.width?.let { key ->
    imageContext.width?.let { contextQuery[key] = it.toInt().toString() }
  }
  imageQueryParams.height?.let { key ->
    imageContext.height?.let { contextQuery[key] = it.toInt().toString() }
  }
  if (contextQuery.isEmpty()) return config
  return config.copy(query = (config.query ?: emptyMap()) + contextQuery)
}

/**
 * Replaces the `{id}` token with the track id in a request config's path, query values, and header
 * values. Used so a `nowPlayingArtwork` like `{ path: "/artwork/{id}" }` resolves. Configs without
 * the token are returned unchanged. Mirrors the iOS `substituteTrackId` helper.
 */
private fun substituteTrackId(config: RequestConfig, id: String): RequestConfig {
  fun sub(s: String?): String? = s?.replace("{id}", id)
  fun subMap(m: Map<String, String>?): Map<String, String>? =
    m?.mapValues { (_, value) -> value.replace("{id}", id) }
  return config.copy(
    path = sub(config.path),
    headers = subMap(config.headers),
    query = subMap(config.query),
  )
}

/** Builds a headers map, merging explicit headers with userAgent and contentType. */
private fun buildHeadersMap(
  headers: Map<String, String>?,
  userAgent: String?,
  contentType: String?,
): Map<String, String>? {
  val mergedHeaders = mutableMapOf<String, String>()
  headers?.let { mergedHeaders.putAll(it) }
  if (userAgent != null && !mergedHeaders.containsKey("User-Agent")) {
    mergedHeaders["User-Agent"] = userAgent
  }
  if (contentType != null && !mergedHeaders.containsKey("Content-Type")) {
    mergedHeaders["Content-Type"] = contentType
  }
  return mergedHeaders.ifEmpty { null }
}
