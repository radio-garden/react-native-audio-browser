/**
 * Utility for handling browser paths and contextual URLs in the media browser system.
 * Mirrors Android's BrowserPathHelper.kt
 *
 * Handles two types of special paths:
 * 1. System paths (prefixed with `/__`): Root, recent, and search paths
 * 2. Contextual URLs: Embed parent context in track identifiers for Media3 integration
 *
 * Contextual URL format: `{parentPath}?__trackId={trackSrc}`
 * Example: "/library/radio?__trackId=song.mp3"
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
   * Strips the __trackId parameter from a contextual URL to get the parent path.
   * If the URL is not contextual, returns it unchanged.
   *
   * @param url The URL to process
   * @returns The URL without the __trackId parameter
   *
   * Example: "/library/radio?__trackId=song.mp3" → "/library/radio"
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

      // Remove the __trackId parameter
      urlObj.searchParams.delete(this.CONTEXTUAL_TRACK_PARAM)

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
        `[?&]${this.CONTEXTUAL_TRACK_PARAM}=[^&]*`,
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

    try {
      // Parse the URL - handle both full URLs and paths
      const isFullUrl =
        path.startsWith('http://') || path.startsWith('https://')
      const urlObj = new URL(path, isFullUrl ? undefined : 'http://placeholder')
      return urlObj.searchParams.get(this.CONTEXTUAL_TRACK_PARAM) ?? undefined
    } catch {
      // Fallback: regex extraction
      const match = path.match(
        new RegExp(`[?&]${this.CONTEXTUAL_TRACK_PARAM}=([^&]*)`)
      )
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
