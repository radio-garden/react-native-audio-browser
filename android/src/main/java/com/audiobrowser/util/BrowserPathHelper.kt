package com.audiobrowser.util

import android.net.Uri
import androidx.core.net.toUri
import com.margelo.nitro.audiobrowser.NavigationErrorKind
import java.net.URLEncoder

/**
 * Builds and parses the library's own browse paths: the `/__` system paths, and contextual paths
 * `{parentPath}?__trackId={identity}&__index={childIndex}`, which give Media3 a media ID for a
 * playable-only track while keeping its parent for queue restoration (docs/adr/0009).
 */
object BrowserPathHelper {
  const val ROOT_PATH = "/__root"

  /** Full search paths are `/__search?q=<query>`; see [createSearchPath]. */
  const val SEARCH_PATH_PREFIX = "/__search"

  /** Placeholder media ID for the offline tile. */
  const val OFFLINE_PATH = "/__offline"

  /** Placeholder media ID for a generic browse error tile. */
  const val ERROR_PATH = "/__error"

  /** Placeholder media ID for a Browse Gate tile (subscription, login or region block). */
  const val GATE_PATH = "/__gate"

  /** Placeholder media ID for a level that resolved with zero children (docs/adr/0001). */
  const val EMPTY_PATH = "/__empty"

  private const val CONTEXTUAL_TRACK_PARAM = "__trackId"

  // Only a tie-breaker between surfaces carrying the same identity: a stale index never selects a
  // different track.
  private const val CONTEXTUAL_INDEX_PARAM = "__index"

  fun navigationErrorKind(path: String): NavigationErrorKind =
    if (path.startsWith("$SEARCH_PATH_PREFIX?")) NavigationErrorKind.SEARCH
    else NavigationErrorKind.BROWSE

  fun isSpecialPath(path: String): Boolean {
    return path == ROOT_PATH || path.startsWith("$SEARCH_PATH_PREFIX?")
  }

  /**
   * The tile sentinels a drill-in dead-ends on (see `deadEndChildren`). [GATE_PATH] is not one: it
   * re-serves the gate tile so the message stays readable.
   */
  fun isDeadEndPath(path: String): Boolean {
    return path == OFFLINE_PATH || path == ERROR_PATH || path == EMPTY_PATH
  }

  fun createSearchPath(query: String): String {
    val encodedQuery = URLEncoder.encode(query, "UTF-8")
    return "$SEARCH_PATH_PREFIX?q=$encodedQuery"
  }

  fun isContextual(path: String): Boolean {
    return path.contains("?$CONTEXTUAL_TRACK_PARAM=") || path.contains("&$CONTEXTUAL_TRACK_PARAM=")
  }

  /**
   * The parent path: [url] without `__trackId` and `__index`, its other query parameters kept.
   * Returns a non-contextual [url] unchanged.
   */
  fun stripTrackId(url: String): String {
    if (!isContextual(url)) {
      return url
    }

    val uri = url.toUri()

    val builder = uri.buildUpon()
    builder.clearQuery()

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
   * Appends the contextual parameters to [parentPath], which may already carry a query.
   *
   * @param trackId the track identity (`id` when non-blank, else `src`; docs/adr/0008)
   * @param index the child's position on the page when tapped
   */
  fun build(parentPath: String, trackId: String, index: Int? = null): String {
    val separator = if (parentPath.contains('?')) '&' else '?'
    val indexParam = if (index != null) "&$CONTEXTUAL_INDEX_PARAM=$index" else ""
    return "$parentPath$separator$CONTEXTUAL_TRACK_PARAM=${Uri.encode(trackId)}$indexParam"
  }

  /** The decoded `__trackId`, or null when [path] is not contextual. */
  fun extractTrackId(path: String): String? {
    if (!isContextual(path)) {
      return null
    }

    val uri = Uri.parse(path)
    return uri.getQueryParameter(CONTEXTUAL_TRACK_PARAM)
  }

  /** The `__index`, or null when [path] is not contextual or its index is missing or negative. */
  fun extractIndex(path: String): Int? {
    if (!isContextual(path)) {
      return null
    }

    val raw = Uri.parse(path).getQueryParameter(CONTEXTUAL_INDEX_PARAM) ?: return null
    return raw.toIntOrNull()?.takeIf { it >= 0 }
  }

  /**
   * Joins [baseUrl] and [path] with exactly one slash. Returns [path] unchanged when it is already
   * an http(s) URL or [baseUrl] is null.
   */
  fun buildUrl(baseUrl: String?, path: String): String {
    if (path.startsWith("http://") || path.startsWith("https://")) {
      return path
    }

    if (baseUrl == null) {
      return path
    }

    val normalizedBase = "${baseUrl.trimEnd('/')}/"
    val normalizedPath = path.trimStart('/')
    return "$normalizedBase$normalizedPath"
  }
}
