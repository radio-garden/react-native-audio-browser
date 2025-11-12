import type {
  RequestConfig,
  TransformableRequestConfig,
  MediaRequestConfig,
  ArtworkRequestConfig,
  ImageSource,
  Track,
  ImageContext,
  ImageQueryParams,
} from '../../types'
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
      userAgent: override.userAgent ?? base.userAgent,
    }
  },

  /**
   * Merges a base RequestConfig with a MediaRequestConfig.
   * Returns the merged config for use in media URL resolution.
   * Supports the transform callback like Android's RequestConfigBuilder.
   *
   * Note: MediaRequestConfig inherits transform from TransformableRequestConfig,
   * which takes (request, routeParams?) directly, not MediaTransformParams.
   */
  async mergeMediaConfig(
    base: RequestConfig,
    override: MediaRequestConfig
  ): Promise<RequestConfig> {
    // Apply transform function if provided - transform result wins completely
    if (override.transform) {
      try {
        // TransformableRequestConfig.transform takes (request, routeParams?) directly
        const transformed = await override.transform(base)
        return transformed
      } catch (e) {
        console.error('Failed to apply media transform function, using base config', e)
        return base
      }
    }

    // No transform - merge static config
    return this.mergeConfig(base, this.toRequestConfig(override))
  },

  /**
   * Merges a base RequestConfig with an ArtworkRequestConfig.
   * Returns the merged config for use in artwork URL resolution.
   */
  mergeArtworkConfig(
    base: RequestConfig,
    override: ArtworkRequestConfig
  ): RequestConfig {
    // On web, we don't support resolve/transform callbacks - just merge the static config
    return this.mergeConfig(base, this.toRequestConfig(override))
  },

  /**
   * Converts a TransformableRequestConfig to a plain RequestConfig.
   */
  toRequestConfig(config: TransformableRequestConfig | MediaRequestConfig | ArtworkRequestConfig): RequestConfig {
    return {
      path: config.path,
      method: config.method,
      baseUrl: config.baseUrl,
      headers: config.headers,
      query: config.query,
      body: config.body,
      contentType: config.contentType,
      userAgent: config.userAgent,
    }
  },

  /**
   * Resolves a media URL using the media configuration.
   * Creates a RequestConfig with the track's src as the path, then builds the URL.
   * Supports the transform callback for URL manipulation.
   *
   * @param src The track's src value (may be relative or absolute)
   * @param mediaConfig The media request configuration
   * @returns The resolved absolute URL
   */
  async resolveMediaUrl(
    src: string,
    mediaConfig: MediaRequestConfig | undefined
  ): Promise<string> {
    if (!mediaConfig) {
      return BrowserPathHelper.buildUrl(undefined, src)
    }

    const config = await this.mergeMediaConfig({ path: src }, mediaConfig)
    return BrowserPathHelper.buildUrl(config.baseUrl, config.path ?? src)
  },

  /**
   * Resolves an artwork URL and creates an ImageSource.
   * Matches Android's artwork URL transformation behavior.
   *
   * @param artworkUrl The artwork URL (may be relative or absolute)
   * @param artworkConfig The artwork request configuration
   * @returns ImageSource with resolved URI, or undefined if no artwork
   */
  resolveArtworkSource(
    artworkUrl: string | undefined,
    artworkConfig: ArtworkRequestConfig | undefined
  ): ImageSource | undefined {
    if (!artworkUrl) return undefined

    const config = artworkConfig
      ? this.mergeArtworkConfig({ path: artworkUrl }, artworkConfig)
      : { path: artworkUrl }

    const resolvedUri = BrowserPathHelper.buildUrl(config.baseUrl, config.path ?? artworkUrl)

    return {
      uri: resolvedUri,
      method: config.method ?? 'GET',
      headers: config.headers,
    }
  },

  /**
   * Resolves an artwork URL asynchronously with full Track access.
   * Supports resolve and transform callbacks from ArtworkRequestConfig.
   * Matches Android's CoilBitmapLoader.transformArtworkUrlForTrack() behavior.
   *
   * The resolution order is:
   * 1. If resolve callback exists, call it with the track to get per-track config
   * 2. Merge base config + resolved config
   * 3. Apply imageQueryParams if context has dimensions
   * 4. Apply transform callback if present
   *
   * @param track The track to resolve artwork for (full Track object)
   * @param artworkConfig The artwork request configuration
   * @param imageContext Optional image context with size hints (width/height)
   * @returns ImageSource with resolved URI, or undefined if no artwork
   */
  async resolveArtworkSourceAsync(
    track: Track,
    artworkConfig: ArtworkRequestConfig | undefined,
    imageContext?: ImageContext
  ): Promise<ImageSource | undefined> {
    const artworkUrl = track.artwork

    // If no config and no track.artwork, nothing to transform
    if (!artworkConfig && !artworkUrl) {
      return undefined
    }

    // If no artwork config, just return the original artwork URL
    if (!artworkConfig) {
      return artworkUrl ? { uri: artworkUrl, method: 'GET' } : undefined
    }

    try {
      // Step 1: Call resolve callback if provided to get per-track config
      let resolvedConfig: RequestConfig | undefined
      if (artworkConfig.resolve) {
        resolvedConfig = await artworkConfig.resolve(track)
        // If resolve callback returned null/undefined, that means no artwork
        if (!resolvedConfig && artworkUrl === undefined) {
          return undefined
        }
      }

      // Step 2: Merge base config + resolved per-track config
      const baseConfig: RequestConfig = { path: artworkUrl }
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

      // Step 4: Apply transform callback if present
      if (artworkConfig.transform) {
        mergedConfig = await artworkConfig.transform({
          request: mergedConfig,
          context: imageContext,
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
        body: mergedConfig.body,
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
   * @param artworkConfig The artwork request configuration
   * @returns Track with artworkSource populated
   */
  transformTrackArtwork(
    track: Track,
    artworkConfig: ArtworkRequestConfig | undefined
  ): Track {
    // If artworkSource is already set, don't override it
    if (track.artworkSource) return track

    const artworkSource = this.resolveArtworkSource(track.artwork, artworkConfig)
    if (!artworkSource) return track

    return {
      ...track,
      artworkSource,
    }
  },

  /**
   * Transforms artwork URLs for an array of tracks.
   *
   * @param tracks Array of tracks to transform
   * @param artworkConfig The artwork request configuration
   * @returns Tracks with artworkSource populated
   */
  transformTracksArtwork(
    tracks: Track[],
    artworkConfig: ArtworkRequestConfig | undefined
  ): Track[] {
    return tracks.map(track => this.transformTrackArtwork(track, artworkConfig))
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
  },
} as const
