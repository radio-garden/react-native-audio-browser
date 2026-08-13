package com.audiobrowser.util

import android.net.Uri
import androidx.core.net.toUri
import java.net.URLEncoder

/**
 * Utility for handling browser paths and contextual URLs in the media browser system.
 *
 * Handles two types of special paths:
 * 1. System paths (prefixed with `/__`): Root, recent, and search paths
 * 2. Contextual URLs: Embed parent context in track identifiers for Media3 integration
 *
 * Contextual URL format: `{parentPath}?__trackId={trackIdentity}&__index={childIndex}` (the
 * identity is the track's `id` when non-blank, else its `src`) Example:
 * "/library/radio?__trackId=song.mp3&__index=2"
 *
 * `__trackId` is the identity check; `__index` (the child's position on the page at stamp time) is
 * only a tie-breaker between surfaces that carry the same identity — a stale index never selects a
 * different track.
 *
 * This allows:
 * - Media3 to reference playable-only tracks (tracks with `src` but no `path`)
 * - Cache lookup to work consistently
 * - Parent context to be preserved for queue restoration
 */
object BrowserPathHelper {
  /** Root path for media browsing */
  const val ROOT_PATH = "/__root"

  /** Recent media path for playback resumption */
  const val RECENT_PATH = "/__recent"

  /** Search path prefix (full path is /__search?q=query) */
  const val SEARCH_PATH_PREFIX = "/__search"

  /** Offline error placeholder media ID */
  const val OFFLINE_PATH = "/__offline"

  /** Generic browse error placeholder media ID */
  const val ERROR_PATH = "/__error"

  /** Browse Gate placeholder media ID (subscription/login/region block) */
  const val GATE_PATH = "/__gate"

  // Query parameter name for contextual track identifiers
  private const val CONTEXTUAL_TRACK_PARAM = "__trackId"

  // Query parameter name for the tapped child's page position (tie-breaker)
  private const val CONTEXTUAL_INDEX_PARAM = "__index"

  /** Check if a path is a special system path (not a regular navigation path) */
  fun isSpecialPath(path: String): Boolean {
    return path == ROOT_PATH || path == RECENT_PATH || path.startsWith("$SEARCH_PATH_PREFIX?")
  }

  /** Create a search path for a given query */
  fun createSearchPath(query: String): String {
    val encodedQuery = URLEncoder.encode(query, "UTF-8")
    return "$SEARCH_PATH_PREFIX?q=$encodedQuery"
  }

  /**
   * Checks if a path contains a contextual track identifier.
   *
   * @param path The URL path to check
   * @return true if the path contains the contextual track parameter
   */
  fun isContextual(path: String): Boolean {
    return path.contains("?$CONTEXTUAL_TRACK_PARAM=") || path.contains("&$CONTEXTUAL_TRACK_PARAM=")
  }

  /**
   * Strips the contextual parameters (__trackId and __index) from a contextual URL to get the
   * parent path. If the URL is not contextual, returns it unchanged.
   *
   * @param url The URL to process
   * @return The URL without the contextual parameters
   *
   * Example: "/library/radio?__trackId=song.mp3&__index=2" → "/library/radio" Example:
   * "/search?q=jazz&__trackId=song.mp3" → "/search?q=jazz"
   */
  fun stripTrackId(url: String): String {
    if (!isContextual(url)) {
      return url
    }

    val uri = url.toUri()

    // Build URL preserving everything except the contextual parameters
    val builder = uri.buildUpon()
    builder.clearQuery()

    // Re-add all query params except __trackId and __index
    uri.queryParameterNames.forEach { paramName ->
      if (paramName != CONTEXTUAL_TRACK_PARAM && paramName != CONTEXTUAL_INDEX_PARAM) {
        uri.getQueryParameters(paramName).forEach { value ->
          builder.appendQueryParameter(paramName, value)
        }
      }
    }

    return builder.build().toString()
  }

  /**
   * Builds a contextual URL by appending a track identifier — and optionally the tapped child's
   * page position — to a parent path. Handles existing query parameters correctly.
   *
   * @param parentPath The parent container path
   * @param trackId The track identity (`id` when non-blank, else `src` — see Track.identity)
   * @param index The child's position on the page at stamp time (tie-breaker)
   * @return A contextual URL combining parent path, track ID, and index
   *
   * Example: build("/library", "song.mp3", 2) → "/library?__trackId=song.mp3&__index=2" Example:
   * build("/search?q=jazz", "song.mp3") → "/search?q=jazz&__trackId=song.mp3"
   */
  fun build(parentPath: String, trackId: String, index: Int? = null): String {
    val separator = if (parentPath.contains('?')) '&' else '?'
    val indexParam = if (index != null) "&$CONTEXTUAL_INDEX_PARAM=$index" else ""
    return "$parentPath$separator$CONTEXTUAL_TRACK_PARAM=${Uri.encode(trackId)}$indexParam"
  }

  /**
   * Extracts the track ID from a contextual URL. Returns null if the URL is not contextual or
   * doesn't contain the track ID parameter.
   *
   * @param path The contextual URL to parse
   * @return The extracted track ID, or null if not found
   *
   * Example: "/library/radio?__trackId=song.mp3" → "song.mp3"
   */
  fun extractTrackId(path: String): String? {
    if (!isContextual(path)) {
      return null
    }

    val uri = Uri.parse(path)
    return uri.getQueryParameter(CONTEXTUAL_TRACK_PARAM)
  }

  /**
   * Extracts the stamped page index from a contextual URL, or null when the URL is not contextual
   * or carries no (valid, non-negative) index.
   *
   * Example: "/library/radio?__trackId=song.mp3&__index=2" → 2
   */
  fun extractIndex(path: String): Int? {
    if (!isContextual(path)) {
      return null
    }

    val raw = Uri.parse(path).getQueryParameter(CONTEXTUAL_INDEX_PARAM) ?: return null
    return raw.toIntOrNull()?.takeIf { it >= 0 }
  }

  /**
   * Combines a base URL with a path, ensuring proper slash handling.
   *
   * @param baseUrl The base URL (can be null)
   * @param path The path to append
   * @return The combined URL with proper slash handling
   *
   * Examples:
   * - buildUrl("http://example.com", "api/test") → "http://example.com/api/test"
   * - buildUrl("http://example.com/", "/api/test") → "http://example.com/api/test"
   * - buildUrl(null, "/api/test") → "/api/test"
   * - buildUrl(null, "http://full.url") → "http://full.url"
   */
  fun buildUrl(baseUrl: String?, path: String): String {
    // If path is already a full URL, return it as-is
    if (path.startsWith("http://") || path.startsWith("https://")) {
      return path
    }

    // If no baseUrl, return path as-is
    if (baseUrl == null) {
      return path
    }

    // Ensure baseUrl ends with / and path doesn't start with /
    val normalizedBase = "${baseUrl.trimEnd('/')}/"
    val normalizedPath = path.trimStart('/')
    return "$normalizedBase$normalizedPath"
  }
}
