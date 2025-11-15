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
 * Contextual URL format: `{parentPath}?__trackId={trackSrc}` Example:
 * "/library/radio?__trackId=song.mp3"
 *
 * This allows:
 * - Media3 to reference playable-only tracks (tracks with `src` but no `url`)
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

  // Query parameter name for contextual track identifiers
  private const val CONTEXTUAL_TRACK_PARAM = "__trackId"

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
   * Strips the __trackId parameter from a contextual URL to get the parent path. If the URL is not
   * contextual, returns it unchanged.
   *
   * @param url The URL to process
   * @return The URL without the __trackId parameter
   *
   * Example: "/library/radio?__trackId=song.mp3" → "/library/radio" Example:
   * "/search?q=jazz&__trackId=song.mp3" → "/search?q=jazz"
   */
  fun stripTrackId(url: String): String {
    if (!isContextual(url)) {
      return url
    }

    val uri = url.toUri()

    // Build URL preserving everything except __trackId parameter
    val builder = uri.buildUpon()
    builder.clearQuery()

    // Re-add all query params except __trackId
    uri.queryParameterNames.forEach { paramName ->
      if (paramName != CONTEXTUAL_TRACK_PARAM) {
        uri.getQueryParameters(paramName).forEach { value ->
          builder.appendQueryParameter(paramName, value)
        }
      }
    }

    return builder.build().toString()
  }

  /**
   * Builds a contextual URL by appending a track identifier to a parent path. Handles existing
   * query parameters correctly.
   *
   * @param parentPath The parent container path
   * @param trackId The track identifier (typically the `src` value)
   * @return A contextual URL combining parent path and track ID
   *
   * Example: build("/library", "song.mp3") → "/library?__trackId=song.mp3" Example:
   * build("/search?q=jazz", "song.mp3") → "/search?q=jazz&__trackId=song.mp3"
   */
  fun build(parentPath: String, trackId: String): String {
    val separator = if (parentPath.contains('?')) '&' else '?'
    return "$parentPath$separator$CONTEXTUAL_TRACK_PARAM=${Uri.encode(trackId)}"
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
