package com.audiobrowser.browser

/**
 * Matches paths against route patterns, ignoring empty segments. `{name}` captures one segment, `*`
 * matches one without capturing, and a final `**` matches zero or more remaining segments, captured
 * as `params["tail"]` when there is at least one. Mirrors iOS `SimpleRouter`.
 */
class SimpleRouter {

  /**
   * The pattern in [routes] that matches [path] with the highest specificity, and its match, or
   * null when none matches. Among equal scores, the first in [routes]' iteration order wins.
   */
  fun <T> findBestMatch(path: String, routes: Map<String, T>): Pair<String, RouteMatch>? {
    return routes.keys
      .mapNotNull { pattern -> matchPattern(pattern, path)?.let { match -> pattern to match } }
      .maxByOrNull { (_, match) -> match.specificity }
  }

  private fun matchPattern(pattern: String, path: String): RouteMatch? {
    val patternSegments = pattern.split('/').filter { it.isNotEmpty() }
    val pathSegments = path.split('/').filter { it.isNotEmpty() }

    val params = mutableMapOf<String, String>()
    var constantSegments = 0
    var parameterSegments = 0
    var wildcardSegments = 0
    var hasTailWildcard = false

    if (patternSegments.isNotEmpty() && patternSegments.last() == "**") {
      hasTailWildcard = true

      if (patternSegments.size - 1 > pathSegments.size) {
        return null
      }

      for (i in 0 until patternSegments.size - 1) {
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

      if (pathSegments.size > patternSegments.size - 1) {
        val remainingSegments = pathSegments.drop(patternSegments.size - 1)
        params["tail"] = remainingSegments.joinToString("/")
      }
    } else {
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

    // Weights, not a strict order: enough `{param}`s and `*`s can outscore one more constant.
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
      patternSegment.startsWith('{') && patternSegment.endsWith('}') -> {
        val paramName = patternSegment.substring(1, patternSegment.length - 1)
        params[paramName] = pathSegment
        true
      }
      patternSegment == "*" -> true
      patternSegment == pathSegment -> true
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
