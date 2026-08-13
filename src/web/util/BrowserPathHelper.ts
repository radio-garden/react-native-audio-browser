/**
 * Utility for handling browser paths and contextual URLs in the media browser system.
 * Mirrors Android's BrowserPathHelper.kt
 *
 * Handles two types of special paths:
 * 1. System paths (prefixed with `/__`): Root, recent, and search paths
 * 2. Contextual URLs: Embed parent context in track identifiers for Media3 integration
 *
 * Contextual URL format: `{parentPath}?__trackId={trackIdentity}&__index={childIndex}`
 * Example: "/library/radio?__trackId=song.mp3&__index=2"
 *
 * `__trackId` is the identity check; `__index` (the child's position on the
 * page at stamp time) is only a tie-breaker between surfaces that carry the
 * same identity — a stale index never selects a different track.
 *
 * This allows:
 * - Media3 to reference playable-only tracks (tracks with `src` but no `url`)
 * - Cache lookup to work consistently
 * - Parent context to be preserved for queue restoration
 */
export const BrowserPathHelper = {
  /** Search path prefix (full path is /__search?q=query) */
  SEARCH_PATH_PREFIX: '/__search',

  /** Query parameter name for contextual track identifiers */
  CONTEXTUAL_TRACK_PARAM: '__trackId',

  /** Query parameter name for the tapped child's page position (tie-breaker) */
  CONTEXTUAL_INDEX_PARAM: '__index',

  /**
   * Create a search path for a given query
   */
  createSearchPath(query: string): string {
    const encodedQuery = encodeURIComponent(query)
    return `${this.SEARCH_PATH_PREFIX}?q=${encodedQuery}`
  },

  /**
   * Checks if a path contains a contextual track identifier.
   *
   * @param path The URL path to check
   * @returns true if the path contains the contextual track parameter
   */
  isContextual(path: string): boolean {
    return (
      path.includes(`?${this.CONTEXTUAL_TRACK_PARAM}=`) ||
      path.includes(`&${this.CONTEXTUAL_TRACK_PARAM}=`)
    )
  },

  /**
   * Strips the contextual parameters (__trackId and __index) from a contextual
   * URL to get the parent path. If the URL is not contextual, returns it unchanged.
   *
   * @param url The URL to process
   * @returns The URL without the contextual parameters
   *
   * Example: "/library/radio?__trackId=song.mp3&__index=2" → "/library/radio"
   * Example: "/search?q=jazz&__trackId=song.mp3" → "/search?q=jazz"
   */
  stripTrackId(url: string): string {
    if (!this.isContextual(url)) {
      return url
    }

    try {
      // Parse the URL - handle both full URLs and paths
      const isFullUrl = url.startsWith('http://') || url.startsWith('https://')
      const urlObj = new URL(url, isFullUrl ? undefined : 'http://placeholder')

      // Remove the contextual parameters
      urlObj.searchParams.delete(this.CONTEXTUAL_TRACK_PARAM)
      urlObj.searchParams.delete(this.CONTEXTUAL_INDEX_PARAM)

      // Return the appropriate format
      if (isFullUrl) {
        return urlObj.toString()
      } else {
        const search = urlObj.searchParams.toString()
        return search ? `${urlObj.pathname}?${search}` : urlObj.pathname
      }
    } catch {
      // Fallback: simple string manipulation
      const paramPattern = new RegExp(
        `[?&](?:${this.CONTEXTUAL_TRACK_PARAM}|${this.CONTEXTUAL_INDEX_PARAM})=[^&]*`,
        'g'
      )
      let result = url.replace(paramPattern, '')
      // Clean up any trailing ? or &
      result = result.replace(/[?&]$/, '')
      // Fix double && or ?&
      result = result.replace(/[?&]{2,}/g, '&').replace(/\?&/, '?')
      return result
    }
  },

  /**
   * Builds a contextual URL by appending a track identifier — and optionally
   * the tapped child's page position — to a parent path.
   *
   * @param parentPath The parent container path
   * @param trackId The track identity (`id` when non-blank, else `src`)
   * @param index The child's position on the page at stamp time (tie-breaker)
   * @returns A contextual URL combining parent path, track ID, and index
   *
   * Example: build("/library", "song.mp3", 2) → "/library?__trackId=song.mp3&__index=2"
   */
  build(parentPath: string, trackId: string, index?: number): string {
    const separator = parentPath.includes('?') ? '&' : '?'
    const indexParam =
      index === undefined ? '' : `&${this.CONTEXTUAL_INDEX_PARAM}=${index}`
    return `${parentPath}${separator}${this.CONTEXTUAL_TRACK_PARAM}=${encodeURIComponent(trackId)}${indexParam}`
  },

  /**
   * Extracts the track ID from a contextual URL.
   * Returns undefined if the URL is not contextual or doesn't contain the track ID parameter.
   *
   * @param path The contextual URL to parse
   * @returns The extracted track ID, or undefined if not found
   *
   * Example: "/library/radio?__trackId=song.mp3" → "song.mp3"
   */
  extractTrackId(path: string): string | undefined {
    if (!this.isContextual(path)) {
      return undefined
    }
    return this.extractParam(path, this.CONTEXTUAL_TRACK_PARAM)
  },

  /**
   * Extracts the stamped page index from a contextual URL, or undefined when
   * the URL is not contextual or carries no (valid, non-negative) index.
   *
   * Example: "/library/radio?__trackId=song.mp3&__index=2" → 2
   */
  extractIndex(path: string): number | undefined {
    if (!this.isContextual(path)) {
      return undefined
    }

    const raw = this.extractParam(path, this.CONTEXTUAL_INDEX_PARAM)
    if (raw === undefined || !/^\d+$/.test(raw)) {
      return undefined
    }
    return Number.parseInt(raw, 10)
  },

  /**
   * Extracts a single query parameter, handling both full URLs and paths,
   * with a regex fallback for input the URL parser rejects.
   */
  extractParam(path: string, param: string): string | undefined {
    try {
      const isFullUrl =
        path.startsWith('http://') || path.startsWith('https://')
      const urlObj = new URL(path, isFullUrl ? undefined : 'http://placeholder')
      return urlObj.searchParams.get(param) ?? undefined
    } catch {
      // Fallback: regex extraction
      const match = path.match(new RegExp(`[?&]${param}=([^&]*)`))
      return match?.[1] ? decodeURIComponent(match[1]) : undefined
    }
  },

  /**
   * Combines a base URL with a path, ensuring proper slash handling.
   *
   * @param baseUrl The base URL (can be undefined)
   * @param path The path to append
   * @returns The combined URL with proper slash handling
   *
   * Examples:
   * - buildUrl("http://example.com", "api/test") → "http://example.com/api/test"
   * - buildUrl("http://example.com/", "/api/test") → "http://example.com/api/test"
   * - buildUrl(undefined, "/api/test") → "/api/test"
   * - buildUrl(undefined, "http://full.url") → "http://full.url"
   */
  buildUrl(baseUrl: string | undefined, path: string): string {
    // If path is already a full URL, return it as-is
    if (path.startsWith('http://') || path.startsWith('https://')) {
      return path
    }

    // If no baseUrl, return path as-is
    if (!baseUrl) {
      return path
    }

    // Ensure baseUrl ends with / and path doesn't start with /
    const normalizedBase = baseUrl.replace(/\/+$/, '') + '/'
    const normalizedPath = path.replace(/^\/+/, '')
    return `${normalizedBase}${normalizedPath}`
  }
} as const
