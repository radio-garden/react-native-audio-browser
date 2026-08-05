import type {
  RequestConfig,
  TransformableRequestConfig,
  MediaRequestConfig,
  ArtworkRequestConfig,
  ImageSource,
  Track,
  ImageContext,
  ImageQueryParams
} from '../../types'
import { BrowserPathHelper } from '../util/BrowserPathHelper'
import { artworkUrl as resolveArtworkUrl } from '../../utils/artwork'

/**
 * Appends query parameters to a URL, handling existing query strings.
 */
function appendQueryParams(
  url: string,
  query: Record<string, string | undefined> | undefined
): string {
  if (!query || Object.keys(query).length === 0) return url

  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null) {
      params.append(key, String(value))
    }
  }
  const queryString = params.toString()
  if (!queryString) return url

  const separator = url.includes('?') ? '&' : '?'
  return url + separator + queryString
}

/**
 * Applies image dimension query parameters to a request config.
 * Maps ImageContext dimensions to query params using the configured param names.
 */
function applyImageQueryParams(
  config: RequestConfig,
  imgParams: ImageQueryParams | undefined,
  context: ImageContext | undefined
): RequestConfig {
  if (!imgParams || !context) return config

  const query: Record<string, string> = { ...config.query }

  if (imgParams.width && context.width) {
    query[imgParams.width] = String(context.width)
  }
  if (imgParams.height && context.height) {
    query[imgParams.height] = String(context.height)
  }

  return { ...config, query }
}

/**
 * Builds and merges request configurations.
 * Mirrors Android's RequestConfigBuilder.kt
 */
export const RequestConfigBuilder = {
  /**
   * Builds a complete URL from a request config.
   * Uses BrowserPathHelper for consistent URL building.
   */
  buildUrl(config: RequestConfig): string {
    const path = config.path ?? ''
    const baseUrl = config.baseUrl

    // Use BrowserPathHelper for consistent URL building
    const url = BrowserPathHelper.buildUrl(baseUrl, path)

    // Add query parameters if any
    return appendQueryParams(url, config.query)
  },

  /**
   * Merges two RequestConfigs, with override values taking precedence.
   * Headers and query params are merged (not replaced).
   */
  mergeConfig(base: RequestConfig, override: RequestConfig): RequestConfig {
    return {
      path: override.path ?? base.path,
      method: override.method ?? base.method,
      baseUrl: override.baseUrl ?? base.baseUrl,
      headers: this.mergeHeaders(base.headers, override.headers),
      query: this.mergeQuery(base.query, override.query),
      body: override.body ?? base.body,
      contentType: override.contentType ?? base.contentType,
      userAgent: override.userAgent ?? base.userAgent
    }
  },

  /**
   * Applies one request-config layer (request / kind / route) onto a base.
   * A layer with a transform wins completely — it receives the base (plus route
   * params) and its result replaces the base; the layer's own static fields are
   * ignored. A layer without a transform merges its static fields over the base.
   * `path` is carried from the base — only a transform may change it.
   *
   * Mirrors native's BrowserManager.applyLayer so web resolves the shared
   * `request` → `<kind>` → route chain identically across platforms.
   */
  async applyLayer(
    base: RequestConfig,
    layer: TransformableRequestConfig | undefined,
    params?: Record<string, string>
  ): Promise<RequestConfig> {
    if (!layer) return base
    // A transform (async and/or sync) wins completely: it receives the base and
    // its result replaces it. When both are set they run as a pipeline — async
    // first, then sync (mirrors native applyLayer).
    if (layer.transform || layer.transformSync) {
      let cfg = base
      if (layer.transform) cfg = await layer.transform(cfg, params)
      if (layer.transformSync) cfg = layer.transformSync(cfg, params)
      return cfg
    }
    return {
      method: layer.method ?? base.method,
      path: base.path,
      baseUrl: layer.baseUrl ?? base.baseUrl,
      headers: this.mergeHeaders(base.headers, layer.headers),
      query: this.mergeQuery(base.query, layer.query),
      body: layer.body ?? base.body,
      contentType: layer.contentType ?? base.contentType,
      userAgent: layer.userAgent ?? base.userAgent
    }
  },

  /**
   * Applies an ordered list of layers onto a base via {@link applyLayer}, each
   * layer receiving the previous one's output. `undefined` layers are skipped.
   * This is the single primitive for the `request → <kind> → route` chain —
   * browse, search, and media all build a base and reduce their layers through
   * it, so the ladder lives in exactly one place.
   */
  async applyLayers(
    base: RequestConfig,
    layers: (TransformableRequestConfig | undefined)[],
    params?: Record<string, string>
  ): Promise<RequestConfig> {
    let merged = base
    for (const layer of layers) {
      merged = await this.applyLayer(merged, layer, params)
    }
    return merged
  },

  /**
   * Converts a TransformableRequestConfig to a plain RequestConfig.
   */
  toRequestConfig(
    config:
      | TransformableRequestConfig
      | MediaRequestConfig
      | ArtworkRequestConfig
  ): RequestConfig {
    return {
      path: config.path,
      method: config.method,
      baseUrl: config.baseUrl,
      headers: config.headers,
      query: config.query,
      body: config.body,
      contentType: config.contentType,
      userAgent: config.userAgent
    }
  },

  /**
   * Resolves a media URL using the media configuration.
   * Creates a RequestConfig with the track's src as the path, then builds the URL.
   * Supports the transform callback for URL manipulation.
   *
   * The shared `request` layer is applied first (its transform runs for media
   * too, per the documented contract — e.g. a dynamic baseUrl), then the media
   * transform / static fields on top. Mirrors native's resolveMediaUrl,
   * including its best-effort behaviour: if a transform throws, fall back to the
   * original `src` rather than failing the load.
   *
   * @param src The track's src value (may be relative or absolute)
   * @param requestConfig The shared request configuration (applied first)
   * @param mediaConfig The media request configuration
   * @returns The resolved absolute URL
   */
  async resolveMediaUrl(
    src: string,
    requestConfig: TransformableRequestConfig | undefined,
    mediaConfig: MediaRequestConfig | undefined
  ): Promise<string> {
    try {
      const config = await this.applyLayers({ path: src }, [
        requestConfig,
        mediaConfig
      ])
      return BrowserPathHelper.buildUrl(config.baseUrl, config.path ?? src)
    } catch (e) {
      console.error('Failed to resolve media URL, using original src', e)
      return BrowserPathHelper.buildUrl(undefined, src)
    }
  },

  /**
   * Resolves an artwork URL and creates an ImageSource.
   * Matches Android's artwork URL transformation behavior.
   *
   * The shared `request` layer's static fields are applied first; this sync path
   * cannot run an async `request.transform`, so transform-based shaping (e.g. a
   * dynamic baseUrl) only applies on the async resolution paths. Queue tracks
   * usually already carry an `artworkSource` resolved at browse-time, so this
   * sync fallback rarely runs.
   *
   * @param artworkUrl The artwork URL (may be relative or absolute)
   * @param requestConfig The shared request configuration (static fields only)
   * @param artworkConfig The artwork request configuration
   * @returns ImageSource with resolved URI, or undefined if no artwork
   */
  resolveArtworkSource(
    artworkUrl: string | undefined,
    requestConfig: TransformableRequestConfig | undefined,
    artworkConfig: ArtworkRequestConfig | undefined
  ): ImageSource | undefined {
    if (!artworkUrl) return undefined

    // Base path stays the artwork URL; the request layer contributes baseUrl /
    // query / headers, then the artwork config overrides on top.
    let config: RequestConfig = { path: artworkUrl }
    if (requestConfig) {
      config = this.mergeConfig(this.toRequestConfig(requestConfig), {
        path: artworkUrl
      })
    }
    if (artworkConfig) {
      config = this.mergeConfig(config, this.toRequestConfig(artworkConfig))
    }

    const resolvedUri = BrowserPathHelper.buildUrl(
      config.baseUrl,
      config.path ?? artworkUrl
    )

    return {
      uri: resolvedUri,
      method: config.method ?? 'GET',
      headers: config.headers
    }
  },

  /**
   * Resolves an artwork URL asynchronously with full Track access.
   * Supports resolve and transform callbacks from ArtworkRequestConfig.
   * Matches the native platforms' BrowserManager resolveArtworkUrl behavior.
   *
   * The resolution order is:
   * 0. Apply the shared `request` layer (its transform runs for artwork too)
   * 1. If resolve callback exists, call it with the track to get per-track config
   * 2. Merge base config + resolved config
   * 3. Apply imageQueryParams if context has dimensions
   * 4. Apply transform callback if present
   *
   * @param track The track to resolve artwork for (full Track object)
   * @param requestConfig The shared request configuration (applied first)
   * @param artworkConfig The artwork request configuration
   * @param imageContext Optional image context with size hints (width/height)
   * @returns ImageSource with resolved URI, or undefined if no artwork
   */
  async resolveArtworkSourceAsync(
    track: Track,
    requestConfig: TransformableRequestConfig | undefined,
    artworkConfig: ArtworkRequestConfig | undefined,
    imageContext?: ImageContext
  ): Promise<ImageSource | undefined> {
    // Collapses a per-appearance pair to one URL: this pipeline produces a
    // single `ImageSource`, and the web fallback renders one <Image>.
    const artworkUrl = resolveArtworkUrl(track.artwork)

    // If no config and no track.artwork, nothing to transform
    if (!artworkConfig && !artworkUrl) {
      return undefined
    }

    // If no artwork config, just return the original artwork URL
    if (!artworkConfig) {
      return artworkUrl ? { uri: artworkUrl, method: 'GET' } : undefined
    }

    try {
      // Step 0: Apply the shared request layer, with track.artwork as the path.
      // Its transform (if any) runs for artwork too — e.g. a dynamic baseUrl.
      const baseConfig = await this.applyLayer({ path: artworkUrl }, requestConfig)

      // Step 1: Per-track resolution — async `resolve` first, then `resolveSync`
      // merged over it (mirrors native).
      let resolvedConfig: RequestConfig | undefined
      if (artworkConfig.resolve) {
        resolvedConfig = await artworkConfig.resolve(track)
      }
      if (artworkConfig.resolveSync) {
        const r = artworkConfig.resolveSync(track)
        resolvedConfig = resolvedConfig ? this.mergeConfig(resolvedConfig, r) : r
      }
      // If a resolver ran but produced nothing and there's no artwork URL, no artwork
      if (
        (artworkConfig.resolve || artworkConfig.resolveSync) &&
        !resolvedConfig &&
        artworkUrl === undefined
      ) {
        return undefined
      }

      // Step 2: Merge base config + resolved per-track config
      let mergedConfig = this.mergeConfig(
        this.mergeConfig(baseConfig, this.toRequestConfig(artworkConfig)),
        resolvedConfig ?? {}
      )

      // Step 3: Apply imageQueryParams if context has dimensions
      mergedConfig = applyImageQueryParams(
        mergedConfig,
        artworkConfig.imageQueryParams,
        imageContext
      )

      // Step 4: Apply transform — async first, then sync (pipeline)
      if (artworkConfig.transform) {
        mergedConfig = await artworkConfig.transform({
          request: mergedConfig,
          context: imageContext
        })
      }
      if (artworkConfig.transformSync) {
        mergedConfig = artworkConfig.transformSync({
          request: mergedConfig,
          context: imageContext
        })
      }

      // Build final URL
      const resolvedUri = BrowserPathHelper.buildUrl(
        mergedConfig.baseUrl,
        mergedConfig.path ?? artworkUrl ?? ''
      )
      const finalUri = appendQueryParams(resolvedUri, mergedConfig.query)

      return {
        uri: finalUri,
        method: mergedConfig.method ?? 'GET',
        headers: mergedConfig.headers,
        body: mergedConfig.body
      }
    } catch (error) {
      // resolve/transform threw - log error, return undefined to avoid broken images
      console.error('Failed to resolve artwork URL:', error)
      return undefined
    }
  },

  /**
   * Transforms a track's artwork URL and populates artworkSource.
   * Leaves the original artwork property unchanged.
   * Matches Android's transformArtworkUrl behavior.
   *
   * @param track The track to transform
   * @param requestConfig The shared request configuration (static fields only)
   * @param artworkConfig The artwork request configuration
   * @returns Track with artworkSource populated
   */
  transformTrackArtwork(
    track: Track,
    requestConfig: TransformableRequestConfig | undefined,
    artworkConfig: ArtworkRequestConfig | undefined
  ): Track {
    // If artworkSource is already set, don't override it
    if (track.artworkSource) return track

    const artworkSource = this.resolveArtworkSource(
      resolveArtworkUrl(track.artwork),
      requestConfig,
      artworkConfig
    )
    if (!artworkSource) return track

    return {
      ...track,
      artworkSource
    }
  },

  /**
   * Merges header maps, with override values taking precedence.
   */
  mergeHeaders(
    base: Record<string, string> | undefined,
    override: Record<string, string> | undefined
  ): Record<string, string> | undefined {
    if (!base) return override
    if (!override) return base
    return { ...base, ...override }
  },

  /**
   * Merges query parameter maps, with override values taking precedence.
   */
  mergeQuery(
    base: Record<string, string> | undefined,
    override: Record<string, string> | undefined
  ): Record<string, string> | undefined {
    if (!base) return override
    if (!override) return base
    return { ...base, ...override }
  }
} as const
