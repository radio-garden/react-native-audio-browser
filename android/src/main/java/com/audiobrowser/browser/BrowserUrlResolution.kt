package com.audiobrowser.browser

import com.audiobrowser.http.RequestConfigBuilder
import com.audiobrowser.util.url
import com.margelo.nitro.audiobrowser.ArtworkRequestConfig
import com.margelo.nitro.audiobrowser.ImageContext
import com.margelo.nitro.audiobrowser.ImageQueryParams
import com.margelo.nitro.audiobrowser.ImageSource
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.MediaTransformParams
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.Track
import timber.log.Timber

// The Android side of iOS `RequestPipeline.swift` and the web stub's `RequestConfigBuilder`: keep
// the layer order the same on all three.

/**
 * Layers the request config, then `media`, over [originalUrl] and runs `media.resolve` for [track].
 * Returns null when neither a request layer nor a media config is set; the caller then plays
 * [originalUrl] as is.
 */
suspend fun BrowserManager.resolveMediaUrl(originalUrl: String, track: Track): MediaRequestConfig? {
  val mediaConfig = config.media
  // A resolver-only request layer counts: its baseUrl still has to reach a relative src.
  val hasRequestLayer = config.request != null || config.requestResolver != null
  if (mediaConfig == null && !hasRequestLayer) return null

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
  return RequestConfigBuilder.applyMediaResolve(mediaLayered, track)
}

/**
 * Resolves [track]'s artwork into an [ImageSource]: request layer, artwork config ([perRouteConfig]
 * replaces `config.artwork`, it does not merge), `resolve`, image query params from [imageContext],
 * transform, then `{id}`. With no artwork config the track's artwork URL is returned untouched.
 * Returns null when there is no artwork, when `resolve`/`resolveSync` yields nothing for a track
 * without an artwork URL (even if the config has a static path), or when any callback throws.
 */
suspend fun BrowserManager.resolveArtworkUrl(
  track: Track,
  perRouteConfig: ArtworkRequestConfig? = null,
  imageContext: ImageContext? = null,
): ImageSource? {
  val effectiveArtworkConfig = perRouteConfig ?: config.artwork
  val trackArtwork = track.artwork?.url?.takeIf { it.isNotEmpty() }

  if (effectiveArtworkConfig == null && trackArtwork == null) return null

  if (effectiveArtworkConfig == null) {
    return trackArtwork?.let { ImageSource(uri = it, method = null, headers = null, body = null) }
  }

  return try {
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

    val asyncResolved =
      effectiveArtworkConfig.resolve?.let {
        RequestConfigBuilder.awaitAsyncConfig(it.invoke(track))
      }
    val syncResolved =
      effectiveArtworkConfig.resolveSync?.let {
        RequestConfigBuilder.awaitSyncConfig(it.invoke(track))
      }
    val resolvedConfig = RequestConfigBuilder.composeResolved(asyncResolved, syncResolved)

    if (
      (effectiveArtworkConfig.resolve != null || effectiveArtworkConfig.resolveSync != null) &&
        resolvedConfig == null &&
        trackArtwork == null
    ) {
      return null
    }

    mergedConfig =
      RequestConfigBuilder.mergeConfig(
        mergedConfig,
        RequestConfigBuilder.toRequestConfig(effectiveArtworkConfig),
      )
    resolvedConfig?.let { mergedConfig = RequestConfigBuilder.mergeConfig(mergedConfig, it) }

    // Before the transform, so the transform can override the size params.
    mergedConfig =
      applyImageQueryParams(mergedConfig, effectiveArtworkConfig.imageQueryParams, imageContext)

    var transformedConfig = mergedConfig
    effectiveArtworkConfig.transform?.let {
      transformedConfig =
        RequestConfigBuilder.awaitAsyncConfig(
          it.invoke(MediaTransformParams(transformedConfig, imageContext))
        )
    }
    effectiveArtworkConfig.transformSync?.let {
      transformedConfig =
        RequestConfigBuilder.awaitSyncConfig(
          it.invoke(MediaTransformParams(transformedConfig, imageContext))
        )
    }

    // A track without an id leaves `{id}` in the URL on purpose: the request then fails visibly,
    // and the warning below says why.
    track.id
      ?.takeIf { it.isNotEmpty() }
      ?.let { transformedConfig = substituteTrackId(transformedConfig, it) }
    val hasUnfilledIdToken = configContainsIdToken(transformedConfig)

    val uri = RequestConfigBuilder.buildUrl(transformedConfig)
    if (uri.isEmpty()) return null

    if (hasUnfilledIdToken) {
      Timber.w("nowPlayingArtwork: track has no id — '{id}' left unfilled in %s", uri)
    }

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
    null
  }
}

/**
 * Re-resolves an artwork [uri] registered in [BrowserManager.artworkResolutions] from its track,
 * with the display size in [imageContext], rather than transforming the already-transformed URI.
 * Returns null for an unregistered [uri].
 */
suspend fun BrowserManager.displayArtworkSource(
  uri: String,
  imageContext: ImageContext?,
): ImageSource? {
  val entry = artworkResolutions.lookup(uri) ?: return null
  return resolveArtworkUrl(entry.track, entry.perRouteConfig, imageContext)
}

/**
 * For an artwork [uri] no track can be found for (evicted from the registry, or restored after
 * process death): fetches [uri] as is, adding only the static headers of the request and artwork
 * configs. No transform or resolve runs, so headers a transform adds are missing. Returns null when
 * there are no headers to add.
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

private const val ID_TOKEN = "{id}"

private fun substituteTrackId(config: RequestConfig, id: String): RequestConfig {
  fun sub(s: String?): String? = s?.replace(ID_TOKEN, id)
  fun subMap(m: Map<String, String>?): Map<String, String>? =
    m?.mapValues { (_, value) -> value.replace(ID_TOKEN, id) }
  return config.copy(
    path = sub(config.path),
    headers = subMap(config.headers),
    query = subMap(config.query),
  )
}

private fun configContainsIdToken(config: RequestConfig): Boolean =
  config.path?.contains(ID_TOKEN) == true ||
    config.query?.values?.any { it.contains(ID_TOKEN) } == true ||
    config.headers?.values?.any { it.contains(ID_TOKEN) } == true

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
