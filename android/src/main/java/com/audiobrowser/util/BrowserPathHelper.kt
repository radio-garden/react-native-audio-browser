package com.audiobrowser.util

import android.net.Uri
import com.margelo.nitro.audiobrowser.NavigationErrorKind
import java.net.URLEncoder

/**
 * Builds and parses the library's own browse paths: the `/__` system paths, and contextual paths
 * `{parentPath}?__trackId={identity}&__index={childIndex}`, which give Media3 a media ID for a
 * playable-only track while keeping its parent for queue restoration (docs/adr/0009).
 */
object BrowserPathHelper {
  private val ABSOLUTE_URL = Regex("^([a-z][a-z0-9+.-]*:|//)", RegexOption.IGNORE_CASE)

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
   * The parent path: [url] without `__trackId` and `__index`, every other character as written, so
   * it equals the parent [build] was given. Returns a non-contextual [url] unchanged.
   */
  fun stripTrackId(url: String): String {
    if (!isContextual(url)) {
      return url
    }
    val (head, pairs) = splitQuery(url) ?: return url
    val kept =
      pairs.filterNot {
        it.startsWith("$CONTEXTUAL_TRACK_PARAM=") || it.startsWith("$CONTEXTUAL_INDEX_PARAM=")
      }
    return if (kept.isEmpty()) head else "$head?${kept.joinToString("&")}"
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
  fun extractTrackId(path: String): String? = contextualValue(CONTEXTUAL_TRACK_PARAM, path)

  /** The `__index`, or null when [path] is not contextual or its index is missing or negative. */
  fun extractIndex(path: String): Int? =
    contextualValue(CONTEXTUAL_INDEX_PARAM, path)?.toIntOrNull()?.takeIf { it >= 0 }

  /**
   * [path] cut at its first `?`: what precedes it, and the `name=value` pairs after it as written.
   */
  private fun splitQuery(path: String): Pair<String, List<String>>? {
    val mark = path.indexOf('?')
    if (mark < 0) {
      return null
    }
    return path.substring(0, mark) to path.substring(mark + 1).split('&')
  }

  /** The percent-decoded value of [name] in a contextual [path]; null when there is none. */
  private fun contextualValue(name: String, path: String): String? {
    if (!isContextual(path)) {
      return null
    }
    val prefix = "$name="
    val pair = splitQuery(path)?.second?.firstOrNull { it.startsWith(prefix) } ?: return null
    return Uri.decode(pair.substring(prefix.length))
  }

  /**
   * Joins [baseUrl] and [path] with exactly one slash. Returns [path] unchanged when it has a URI
   * scheme (`content:`, `file:`, `HTTPS:`, …), starts with `//` or [baseUrl] is null.
   */
  fun buildUrl(baseUrl: String?, path: String): String {
    if (baseUrl == null || isAbsoluteUrl(path)) {
      return path
    }

    val normalizedBase = "${baseUrl.trimEnd('/')}/"
    val normalizedPath = path.trimStart('/')
    return "$normalizedBase$normalizedPath"
  }

  private fun isAbsoluteUrl(url: String): Boolean = ABSOLUTE_URL.containsMatchIn(url)

  /** Whether [url] is a `data:` URL, whose bytes are the URL itself. */
  fun isDataUrl(url: String): Boolean = url.startsWith("data:", ignoreCase = true)
}
