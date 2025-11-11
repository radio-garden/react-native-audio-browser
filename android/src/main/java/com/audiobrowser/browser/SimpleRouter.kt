package com.audiobrowser.browser

/**
 * Simple route matcher for path patterns with parameter extraction.
 *
 * Supports patterns like:
 * - /artists - exact match
 * - /artists/{id} - parameter extraction
 * - /artists/{id}/albums - multiple segments with parameters
 * - /artists/&#42; - wildcard match (matches any single segment)
 * - /files/&#42;&#42; - tail wildcard (matches remaining path segments)
 *
 * Route specificity rules:
 * - More path segments = higher specificity
 * - Constant segments > parameter segments > wildcards > tail wildcards
 * - Most specific route wins
 */
class SimpleRouter {

  /**
   * Finds the best matching route for the given path.
   *
   * @param path The incoming path to match (e.g., "/artists/123")
   * @param routes Map of route patterns to match against
   * @return Pair of (route pattern, match result) or null if no match
   */
  fun <T> findBestMatch(path: String, routes: Map<String, T>): Pair<String, RouteMatch>? {
    return routes.keys
      .mapNotNull { pattern -> matchPattern(pattern, path)?.let { match -> pattern to match } }
      .maxByOrNull { (_, match) -> match.specificity }
  }

  /**
   * Attempts to match a single pattern against a path.
   *
   * @param pattern Route pattern (e.g., "/artists/{id}", "/files/&#42;", "/api/&#42;&#42;")
   * @param path Incoming path (e.g., "/artists/123")
   * @return RouteMatch if successful, null otherwise
   */
  private fun matchPattern(pattern: String, path: String): RouteMatch? {
    val patternSegments = pattern.split('/').filter { it.isNotEmpty() }
    val pathSegments = path.split('/').filter { it.isNotEmpty() }

    val params = mutableMapOf<String, String>()
    var constantSegments = 0
    var parameterSegments = 0
    var wildcardSegments = 0
    var hasTailWildcard = false

    // Check for tail wildcard at end
    if (patternSegments.isNotEmpty() && patternSegments.last() == "**") {
      hasTailWildcard = true

      // For tail wildcard, pattern must have <= path segments
      if (patternSegments.size - 1 > pathSegments.size) {
        return null
      }

      // Match all segments except the tail wildcard
      for (i in 0 until patternSegments.size - 1) {
        if (!matchSingleSegment(patternSegments[i], pathSegments[i], params)) {
          return null
        }
        updateSegmentCounts(
          patternSegments[i],
          constantSegments,
          parameterSegments,
          wildcardSegments,
        )
      }

      // Capture remaining path segments as "tail" parameter
      if (pathSegments.size > patternSegments.size - 1) {
        val remainingSegments = pathSegments.drop(patternSegments.size - 1)
        params["tail"] = remainingSegments.joinToString("/")
      }
    } else {
      // Normal matching - must have same number of segments
      if (patternSegments.size != pathSegments.size) {
        return null
      }

      for (i in patternSegments.indices) {
        if (!matchSingleSegment(patternSegments[i], pathSegments[i], params)) {
          return null
        }
        val (constCount, paramCount, wildcardCount) =
          updateSegmentCounts(
            patternSegments[i],
            constantSegments,
            parameterSegments,
            wildcardSegments,
          )
        constantSegments = constCount
        parameterSegments = paramCount
        wildcardSegments = wildcardCount
      }
    }

    // Specificity calculation (higher = more specific):
    // - Constants: 1000 points each
    // - Parameters: 100 points each
    // - Single wildcards: 10 points each
    // - Tail wildcard: 1 point
    // - Base path length: segments count
    val specificity =
      (constantSegments * 1000) +
        (parameterSegments * 100) +
        (wildcardSegments * 10) +
        (if (hasTailWildcard) 1 else 0) +
        patternSegments.size

    return RouteMatch(params, specificity)
  }

  private fun matchSingleSegment(
    patternSegment: String,
    pathSegment: String,
    params: MutableMap<String, String>,
  ): Boolean {
    return when {
      // Parameter segment: {paramName}
      patternSegment.startsWith('{') && patternSegment.endsWith('}') -> {
        val paramName = patternSegment.substring(1, patternSegment.length - 1)
        params[paramName] = pathSegment
        true
      }
      // Single wildcard: *
      patternSegment == "*" -> true
      // Constant segment: must match exactly
      patternSegment == pathSegment -> true
      // Mismatch
      else -> false
    }
  }

  private fun updateSegmentCounts(
    patternSegment: String,
    constantSegments: Int,
    parameterSegments: Int,
    wildcardSegments: Int,
  ): Triple<Int, Int, Int> {
    return when {
      patternSegment.startsWith('{') && patternSegment.endsWith('}') ->
        Triple(constantSegments, parameterSegments + 1, wildcardSegments)
      patternSegment == "*" -> Triple(constantSegments, parameterSegments, wildcardSegments + 1)
      else -> Triple(constantSegments + 1, parameterSegments, wildcardSegments)
    }
  }
}
