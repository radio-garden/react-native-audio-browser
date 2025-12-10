import Foundation

/// Result of a successful route match.
struct RouteMatch {
  /// Extracted parameters from the path (e.g., {id} → "123")
  /// For tail wildcards, the remaining path is stored in params["tail"]
  let params: [String: String]

  /// Specificity score (higher = more specific match)
  let specificity: Int

  init(params: [String: String] = [:], specificity: Int = 0) {
    self.params = params
    self.specificity = specificity
  }
}

/// Protocol for route entries that can be matched.
protocol RouteEntry {
  var path: String { get }
}

/// Simple route matcher for path patterns with parameter extraction.
///
/// Supports patterns like:
/// - /artists - exact match
/// - /artists/{id} - parameter extraction
/// - /artists/{id}/albums - multiple segments with parameters
/// - /artists/* - wildcard match (matches any single segment)
/// - /files/** - tail wildcard (matches remaining path segments)
///
/// Route specificity rules:
/// - More path segments = higher specificity
/// - Constant segments > parameter segments > wildcards > tail wildcards
/// - Most specific route wins
final class SimpleRouter {
  /// Finds the best matching route for the given path.
  ///
  /// - Parameters:
  ///   - path: The incoming path to match (e.g., "/artists/123")
  ///   - routes: Array of route entries to match against
  /// - Returns: Tuple of (route, match result) or nil if no match
  func findBestMatch<T: RouteEntry>(path: String, routes: [T]) -> (route: T, match: RouteMatch)? {
    var bestMatch: (route: T, match: RouteMatch)?

    for route in routes {
      if let match = matchPattern(pattern: route.path, path: path) {
        if bestMatch == nil || match.specificity > bestMatch!.match.specificity {
          bestMatch = (route, match)
        }
      }
    }

    return bestMatch
  }

  /// Attempts to match a single pattern against a path.
  ///
  /// - Parameters:
  ///   - pattern: Route pattern (e.g., "/artists/{id}", "/files/*", "/api/**")
  ///   - path: Incoming path (e.g., "/artists/123")
  /// - Returns: RouteMatch if successful, nil otherwise
  private func matchPattern(pattern: String, path: String) -> RouteMatch? {
    let patternSegments = pattern.split(separator: "/").map(String.init)
    let pathSegments = path.split(separator: "/").map(String.init)

    var params: [String: String] = [:]
    var constantSegments = 0
    var parameterSegments = 0
    var wildcardSegments = 0
    var hasTailWildcard = false

    // Check for tail wildcard at end
    if let lastSegment = patternSegments.last, lastSegment == "**" {
      hasTailWildcard = true

      // For tail wildcard, pattern must have <= path segments
      if patternSegments.count - 1 > pathSegments.count {
        return nil
      }

      // Match all segments except the tail wildcard
      for i in 0 ..< (patternSegments.count - 1) {
        guard
          matchSingleSegment(
            patternSegment: patternSegments[i],
            pathSegment: pathSegments[i],
            params: &params,
          )
        else {
          return nil
        }

        updateSegmentCounts(
          patternSegment: patternSegments[i],
          constantSegments: &constantSegments,
          parameterSegments: &parameterSegments,
          wildcardSegments: &wildcardSegments,
        )
      }

      // Capture remaining path segments as "tail" parameter (matches Kotlin behavior)
      if pathSegments.count > patternSegments.count - 1 {
        let remainingSegments = pathSegments.dropFirst(patternSegments.count - 1)
        params["tail"] = remainingSegments.joined(separator: "/")
      }
    } else {
      // Normal matching - must have same number of segments
      if patternSegments.count != pathSegments.count {
        return nil
      }

      for i in patternSegments.indices {
        guard
          matchSingleSegment(
            patternSegment: patternSegments[i],
            pathSegment: pathSegments[i],
            params: &params,
          )
        else {
          return nil
        }

        updateSegmentCounts(
          patternSegment: patternSegments[i],
          constantSegments: &constantSegments,
          parameterSegments: &parameterSegments,
          wildcardSegments: &wildcardSegments,
        )
      }
    }

    // Specificity calculation (higher = more specific):
    // - Constants: 1000 points each
    // - Parameters: 100 points each
    // - Single wildcards: 10 points each
    // - Tail wildcard: 1 point
    // - Base path length: segments count
    let specificity =
      (constantSegments * 1000)
        + (parameterSegments * 100)
        + (wildcardSegments * 10)
        + (hasTailWildcard ? 1 : 0)
        + patternSegments.count

    return RouteMatch(params: params, specificity: specificity)
  }

  private func matchSingleSegment(
    patternSegment: String,
    pathSegment: String,
    params: inout [String: String],
  ) -> Bool {
    // Parameter segment: {paramName}
    if patternSegment.hasPrefix("{"), patternSegment.hasSuffix("}") {
      let paramName = String(patternSegment.dropFirst().dropLast())
      params[paramName] = pathSegment
      return true
    }

    // Single wildcard: *
    if patternSegment == "*" {
      return true
    }

    // Constant segment: must match exactly
    return patternSegment == pathSegment
  }

  private func updateSegmentCounts(
    patternSegment: String,
    constantSegments: inout Int,
    parameterSegments: inout Int,
    wildcardSegments: inout Int,
  ) {
    if patternSegment.hasPrefix("{"), patternSegment.hasSuffix("}") {
      parameterSegments += 1
    } else if patternSegment == "*" {
      wildcardSegments += 1
    } else {
      constantSegments += 1
    }
  }
}
