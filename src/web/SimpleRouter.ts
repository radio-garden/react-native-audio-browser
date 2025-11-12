/**
 * Simple route matcher for path patterns with parameter extraction.
 *
 * Supports patterns like:
 * - /artists - exact match
 * - /artists/{id} - parameter extraction
 * - /artists/{id}/albums - multiple segments with parameters
 * - /artists/* - wildcard match (matches any single segment)
 * - /files/** - tail wildcard (matches remaining path segments)
 *
 * Route specificity rules:
 * - More path segments = higher specificity
 * - Constant segments > parameter segments > wildcards > tail wildcards
 * - Most specific route wins
 */

export interface RouteMatch {
  params: Record<string, string>
  specificity: number
}

export class SimpleRouter {
  /**
   * Finds the best matching route for the given path.
   *
   * @param path The incoming path to match (e.g., "/artists/123")
   * @param routes Map of route patterns to match against
   * @return Tuple of [route pattern, match result] or null if no match
   */
  findBestMatch<T>(
    path: string,
    routes: Record<string, T>
  ): [string, RouteMatch] | null {
    const matches: Array<[string, RouteMatch]> = []

    for (const pattern of Object.keys(routes)) {
      const match = this.matchPattern(pattern, path)
      if (match) {
        matches.push([pattern, match])
      }
    }

    if (matches.length === 0) {
      return null
    }

    // Find the match with highest specificity
    return matches.reduce((best, current) =>
      current[1].specificity > best[1].specificity ? current : best
    )
  }

  /**
   * Attempts to match a single pattern against a path.
   *
   * @param pattern Route pattern (e.g., "/artists/{id}", "/files/*", "/api/**")
   * @param path Incoming path (e.g., "/artists/123")
   * @return RouteMatch if successful, null otherwise
   */
  private matchPattern(pattern: string, path: string): RouteMatch | null {
    const patternSegments = pattern.split('/').filter((s) => s.length > 0)
    const pathSegments = path.split('/').filter((s) => s.length > 0)

    const params: Record<string, string> = {}
    let constantSegments = 0
    let parameterSegments = 0
    let wildcardSegments = 0
    let hasTailWildcard = false

    // Check for tail wildcard at end
    if (
      patternSegments.length > 0 &&
      patternSegments[patternSegments.length - 1] === '**'
    ) {
      hasTailWildcard = true

      // For tail wildcard, pattern must have <= path segments
      if (patternSegments.length - 1 > pathSegments.length) {
        return null
      }

      // Match all segments except the tail wildcard
      for (let i = 0; i < patternSegments.length - 1; i++) {
        if (!this.matchSingleSegment(patternSegments[i], pathSegments[i], params)) {
          return null
        }
        const counts = this.updateSegmentCounts(
          patternSegments[i],
          constantSegments,
          parameterSegments,
          wildcardSegments
        )
        constantSegments = counts.constantSegments
        parameterSegments = counts.parameterSegments
        wildcardSegments = counts.wildcardSegments
      }

      // Capture remaining path segments as "tail" parameter
      if (pathSegments.length > patternSegments.length - 1) {
        const remainingSegments = pathSegments.slice(patternSegments.length - 1)
        params['tail'] = remainingSegments.join('/')
      }
    } else {
      // Normal matching - must have same number of segments
      if (patternSegments.length !== pathSegments.length) {
        return null
      }

      // Match each segment
      for (let i = 0; i < patternSegments.length; i++) {
        if (!this.matchSingleSegment(patternSegments[i], pathSegments[i], params)) {
          return null
        }
        const counts = this.updateSegmentCounts(
          patternSegments[i],
          constantSegments,
          parameterSegments,
          wildcardSegments
        )
        constantSegments = counts.constantSegments
        parameterSegments = counts.parameterSegments
        wildcardSegments = counts.wildcardSegments
      }
    }

    // Calculate specificity score
    // Higher scores are more specific
    const specificity = this.calculateSpecificity(
      constantSegments,
      parameterSegments,
      wildcardSegments,
      hasTailWildcard
    )

    return { params, specificity }
  }

  /**
   * Matches a single segment of the pattern against a path segment.
   *
   * @param patternSegment Single pattern segment (e.g., "{id}", "*", "artists")
   * @param pathSegment Single path segment (e.g., "123")
   * @param params Map to store extracted parameters
   * @return true if match successful
   */
  private matchSingleSegment(
    patternSegment: string,
    pathSegment: string | undefined,
    params: Record<string, string>
  ): boolean {
    if (pathSegment === undefined) {
      return false
    }

    // Parameter segment: {paramName}
    if (patternSegment.startsWith('{') && patternSegment.endsWith('}')) {
      const paramName = patternSegment.slice(1, -1)
      params[paramName] = pathSegment
      return true
    }

    // Wildcard segment: *
    if (patternSegment === '*') {
      return true
    }

    // Constant segment: must match exactly
    return patternSegment === pathSegment
  }

  /**
   * Updates segment counts based on the pattern segment type.
   */
  private updateSegmentCounts(
    patternSegment: string,
    constantSegments: number,
    parameterSegments: number,
    wildcardSegments: number
  ): {
    constantSegments: number
    parameterSegments: number
    wildcardSegments: number
  } {
    if (patternSegment.startsWith('{') && patternSegment.endsWith('}')) {
      return {
        constantSegments,
        parameterSegments: parameterSegments + 1,
        wildcardSegments,
      }
    } else if (patternSegment === '*') {
      return {
        constantSegments,
        parameterSegments,
        wildcardSegments: wildcardSegments + 1,
      }
    } else {
      return {
        constantSegments: constantSegments + 1,
        parameterSegments,
        wildcardSegments,
      }
    }
  }

  /**
   * Calculates specificity score for a route match.
   *
   * Specificity rules:
   * - Each constant segment: +1000
   * - Each parameter segment: +100
   * - Each wildcard segment: +10
   * - Tail wildcard: +1
   */
  private calculateSpecificity(
    constantSegments: number,
    parameterSegments: number,
    wildcardSegments: number,
    hasTailWildcard: boolean
  ): number {
    let score = 0
    score += constantSegments * 1000
    score += parameterSegments * 100
    score += wildcardSegments * 10
    if (hasTailWildcard) {
      score += 1
    }
    return score
  }
}
