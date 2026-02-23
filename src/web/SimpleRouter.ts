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

import { assertedNotNullish } from '../utils/validation'

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
        if (
          !this.matchSingleSegment(
            assertedNotNullish(patternSegments[i]),
            assertedNotNullish(pathSegments[i]),
            params
          )
        ) {
          return null
        }
        // Note: The Kotlin implementation doesn't update counts here (appears to be a bug)
        // but we'll match it exactly for consistency
        this.updateSegmentCounts(
          assertedNotNullish(patternSegments[i]),
          constantSegments,
          parameterSegments,
          wildcardSegments
        )
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

      for (let i = 0; i < patternSegments.length; i++) {
        if (
          !this.matchSingleSegment(
            assertedNotNullish(patternSegments[i]),
            assertedNotNullish(pathSegments[i]),
            params
          )
        ) {
          return null
        }
        const [constCount, paramCount, wildcardCount] =
          this.updateSegmentCounts(
            assertedNotNullish(patternSegments[i]),
            constantSegments,
            parameterSegments,
            wildcardSegments
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
    const specificity =
      constantSegments * 1000 +
      parameterSegments * 100 +
      wildcardSegments * 10 +
      (hasTailWildcard ? 1 : 0) +
      patternSegments.length

    return { params, specificity }
  }

  private matchSingleSegment(
    patternSegment: string,
    pathSegment: string,
    params: Record<string, string>
  ): boolean {
    // Parameter segment: {paramName}
    if (patternSegment.startsWith('{') && patternSegment.endsWith('}')) {
      const paramName = patternSegment.substring(1, patternSegment.length - 1)
      params[paramName] = pathSegment
      return true
    }
    // Single wildcard: *
    if (patternSegment === '*') {
      return true
    }
    // Constant segment: must match exactly
    if (patternSegment === pathSegment) {
      return true
    }
    // Mismatch
    return false
  }

  private updateSegmentCounts(
    patternSegment: string,
    constantSegments: number,
    parameterSegments: number,
    wildcardSegments: number
  ): [number, number, number] {
    if (patternSegment.startsWith('{') && patternSegment.endsWith('}')) {
      return [constantSegments, parameterSegments + 1, wildcardSegments]
    } else if (patternSegment === '*') {
      return [constantSegments, parameterSegments, wildcardSegments + 1]
    } else {
      return [constantSegments + 1, parameterSegments, wildcardSegments]
    }
  }
}
