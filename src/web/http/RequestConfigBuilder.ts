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
import { artworkUrl as resolveArtworkUrl } from '../../utils/artwork'
import { BrowserPathHelper } from '../util/BrowserPathHelper'

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

/** Whether `after` carries a header `before` had not already set identically. */
function addsHeaders(
  before: Record<string, string> | undefined,
  after: Record<string, string> | undefined
): boolean {
  if (!after) return false
  return Object.entries(after).some(([key, value]) => before?.[key] !== value)
}

export interface ResolvedMediaRequest extends RequestConfig {
  /**
   * Whether the `media` layer set a header the shared `request` layer had not
   * already set identically. A media-specific header is the signal that media
   * is separately authenticated, which is what the web redirect probe gates
   * on.
   *
   * By value, not by declaration: a media layer re-declaring a shared header
   * with the same value reads as no contribution, which is what keeps an
   * identity transform from tripping the probe.
   */
  mediaLayerHeaders: boolean
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
   * `path` is carried from the base — only a transform may change it. A
   * throwing transform falls back to the base, so the layer is skipped and the
   * ladder continues.
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
      try {
        let cfg = base
        if (layer.transform) cfg = await layer.transform(cfg, params)
        if (layer.transformSync) cfg = layer.transformSync(cfg, params)
        return cfg
      } catch (e) {
        // A throwing transform costs its own layer, not the ladder: the layers
        // under it stand, so a relative path still gets the shared `baseUrl`
        // and a media request keeps the shared credential. One catch around
        // the pair, as native has it, so a sync stage that throws after a
        // successful async one also falls back to the layer's input rather
        // than to a half-applied config.
        console.error(
          'Failed to apply transform function, using base config',
          e
        )
        return base
      }
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
   * Resolves a media request from the layered configuration, returning the
   * whole request rather than just its URL.
   *
   * The layers resolve headers (and a user agent) alongside the URL, and the
   * media request needs all of them: native applies them to the AVURLAsset /
   * ExoPlayer DataSpec, so web has to apply them to Shaka's requests or the
   * same configuration authenticates on iOS and Android but 401s here.
   *
   * The resolved URL is returned in `path`, with `baseUrl` already folded into
   * it. Best-effort throughout, but at two levels: a throwing transform or
   * resolver costs only its own layer (see {@link applyLayer}), and the catch
   * here is the backstop for anything left — a malformed `baseUrl` reaching
   * `buildUrl`, say — which falls back to the bare `src`.
   *
   * The chain is `request` → `media` → `media.resolve(track)`, matching
   * native's. See {@link applyMediaResolve} for why the per-track resolver goes
   * last.
   *
   * @param src The track's src value (may be relative or absolute)
   * @param requestConfig The shared request configuration (applied first)
   * @param mediaConfig The media request configuration
   * @param track The track being loaded, for `media.resolve`; without it the
   *   per-track layer is skipped and the static layers stand
   * @returns The resolved request, its URL in `path`
   */
  async resolveMediaRequest(
    src: string,
    requestConfig: TransformableRequestConfig | undefined,
    mediaConfig: MediaRequestConfig | undefined,
    track?: Track
  ): Promise<ResolvedMediaRequest> {
    try {
      // The layers are applied one at a time rather than through `applyLayers`
      // so the media layer's own header contribution stays visible; each
      // transform still runs exactly once.
      const shared = await this.applyLayer({ path: src }, requestConfig)
      const layered = await this.applyLayer(shared, mediaConfig)
      const config = await this.applyMediaResolve(layered, mediaConfig, track)
      // buildUrl folds in baseUrl *and* appends `query`; both are cleared on
      // the way out so the resolved url can't be rebuilt and double-applied.
      return {
        ...config,
        baseUrl: undefined,
        query: undefined,
        path: this.buildUrl({ ...config, path: config.path ?? src }),
        mediaLayerHeaders: addsHeaders(shared.headers, config.headers)
      }
    } catch (e) {
      console.error('Failed to resolve media URL, using original src', e)
      return {
        path: BrowserPathHelper.buildUrl(undefined, src),
        mediaLayerHeaders: false
      }
    }
  },

  /**
   * Applies `media.resolve(track)` / `media.resolveSync(track)` as the final,
   * most-specific layer over an already request+media-layered config. A no-op
   * without a track or a resolver, so the layers stand on their own.
   *
   * Last, not first: native runs the media layer's transform and then lets the
   * per-track resolver win over the result (Android `applyMediaResolve`, iOS
   * `applyMediaResolveLayer`), so a resolver minting a signed URL is not
   * overwritten by the static layer that shaped the unsigned one. Override-wins
   * on every field, `path` included — replacing the URL outright is the point.
   *
   * A throwing resolver falls back to the layered config, as native does: the
   * per-track shaping is lost, the layers under it are not.
   */
  async applyMediaResolve(
    layered: RequestConfig,
    mediaConfig: MediaRequestConfig | undefined,
    track: Track | undefined
  ): Promise<RequestConfig> {
    if (!track || !mediaConfig) return layered
    if (!mediaConfig.resolve && !mediaConfig.resolveSync) return layered

    try {
      const resolved = await this.composeResolved(mediaConfig, track)
      return resolved ? this.mergeConfig(layered, resolved) : layered
    } catch (e) {
      console.error('Failed to apply media.resolve, using layered config', e)
      return layered
    }
  },

  /**
   * Runs a config's per-track resolvers and composes their output: async
   * `resolve` first, then `resolveSync` merged over it, sync winning.
   * `undefined` when neither is set or neither produced a config.
   *
   * Media and artwork share the pairing, so it lives here rather than in both
   * — the same reason native has `composeResolved`. Errors are the callers' to
   * handle: the two paths differ on what a throwing resolver costs.
   */
  async composeResolved(
    config: Pick<MediaRequestConfig, 'resolve' | 'resolveSync'>,
    track: Track
  ): Promise<RequestConfig | undefined> {
    let resolved: RequestConfig | undefined
    if (config.resolve) resolved = await config.resolve(track)
    if (config.resolveSync) {
      const sync = config.resolveSync(track)
      resolved = resolved ? this.mergeConfig(resolved, sync) : sync
    }
    return resolved
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
      const baseConfig = await this.applyLayer(
        { path: artworkUrl },
        requestConfig
      )

      // Step 1: Per-track resolution — async `resolve` first, then `resolveSync`
      // merged over it (mirrors native).
      const resolvedConfig = await this.composeResolved(artworkConfig, track)
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
