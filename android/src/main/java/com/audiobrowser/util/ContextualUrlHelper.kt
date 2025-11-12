package com.audiobrowser.util

import android.net.Uri
import androidx.core.net.toUri

/**
 * Utility for handling contextual URLs in the media browser system.
 *
 * Contextual URLs embed parent context in track identifiers for Media3 integration, allowing
 * Android Auto to reference tracks without browsable URLs.
 *
 * Format: `{parentPath}?__trackId={trackSrc}` Example: "/library/radio?__trackId=song.mp3"
 *
 * This allows:
 * - Media3 to reference playable-only tracks (tracks with `src` but no `url`)
 * - Cache lookup to work consistently
 * - Parent context to be preserved for queue restoration
 */
object ContextualUrlHelper {
  // Query parameter name for contextual track identifiers
  private const val CONTEXTUAL_TRACK_PARAM = "__trackId"

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
   * Strips the __trackId parameter from a contextual URL to get the parent path.
   * If the URL is not contextual, returns it unchanged.
   *
   * @param url The URL to process
   * @return The URL without the __trackId parameter
   *
   * Example: "/library/radio?__trackId=song.mp3" → "/library/radio"
   * Example: "/search?q=jazz&__trackId=song.mp3" → "/search?q=jazz"
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
}
