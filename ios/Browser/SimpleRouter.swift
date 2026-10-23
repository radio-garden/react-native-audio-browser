import Foundation

/// Result of a successful route match.
struct RouteMatch {
  /// Extracted parameters from the path (e.g., {id} → "123")
  /// For tail wildcards, the remaining path is stored in params["tail"]
  let params: [String: String]

  /// Specificity score (higher = more specific match)
  let specificity: Int
}

/// Protocol for route entries that can be matched.
protocol RouteEntry {
  var path: String { get }
}

/// Route pattern matching with parameter extraction.
///
/// Supports patterns like:
/// - /artists - exact match
/// - /artists/{id} - parameter extraction
/// - /artists/* - wildcard match (matches any single segment)
/// - /files/** - tail wildcard (matches remaining path segments, captured as `tail`)
///
/// The most specific route wins, the first on a tie: constant segments over parameters over
/// wildcards over the tail wildcard, then more segments.
enum SimpleRouter {
  /// The route the top-level `browse:` source is flattened into.
  static let defaultRoutePath = "__default__"
  static let tabsRoutePath = "__tabs__"
  static let searchRoutePath = "__search__"

  /// The route that browses `path`: the best match among the routes that are not tabs, search or
  /// the default, else the default route with `path` as its `path` param.
  static func browseRoute<T: RouteEntry>(for path: String, in routes: [T]) -> (route: T, match: RouteMatch)? {
    let browseRoutes = routes.filter {
      $0.path != tabsRoutePath && $0.path != searchRoutePath && $0.path != defaultRoutePath
    }
    if let match = findBestMatch(path: path, routes: browseRoutes) {
      return match
    }
    guard let defaultRoute = routes.first(where: { $0.path == defaultRoutePath }) else { return nil }
    return (defaultRoute, RouteMatch(params: ["path": path], specificity: 0))
  }

  /// The most specific of `routes` matching `path`, or nil when none does.
  static func findBestMatch<T: RouteEntry>(path: String, routes: [T]) -> (route: T, match: RouteMatch)? {
    var best: (route: T, match: RouteMatch)?
    for route in routes {
      guard let match = match(pattern: route.path, path: path) else { continue }
      if best.map({ match.specificity > $0.match.specificity }) ?? true {
        best = (route, match)
      }
    }
    return best
  }

  private enum Segment {
    case constant(Substring)
    case param(String)
    case wildcard

    init(_ segment: Substring) {
      if segment.hasPrefix("{"), segment.hasSuffix("}") {
        self = .param(String(segment.dropFirst().dropLast()))
      } else if segment == "*" {
        self = .wildcard
      } else {
        self = .constant(segment)
      }
    }

    var specificity: Int {
      switch self {
      case .constant: 1000
      case .param: 100
      case .wildcard: 10
      }
    }
  }

  private static func match(pattern: String, path: String) -> RouteMatch? {
    var patternSegments = pattern.split(separator: "/")
    let hasTailWildcard = patternSegments.last == "**"
    if hasTailWildcard { patternSegments.removeLast() }
    let pathSegments = path.split(separator: "/")
    guard hasTailWildcard
      ? pathSegments.count >= patternSegments.count
      : pathSegments.count == patternSegments.count
    else { return nil }

    var params: [String: String] = [:]
    // Each segment counts once more, the tail wildcard twice (its segment and its own point).
    var specificity = patternSegments.count + (hasTailWildcard ? 2 : 0)
    for (patternSegment, pathSegment) in zip(patternSegments, pathSegments) {
      let segment = Segment(patternSegment)
      switch segment {
      case let .constant(constant):
        guard constant == pathSegment else { return nil }
      case let .param(name):
        params[name] = String(pathSegment)
      case .wildcard:
        break
      }
      specificity += segment.specificity
    }
    if hasTailWildcard, pathSegments.count > patternSegments.count {
      params["tail"] = pathSegments.dropFirst(patternSegments.count).joined(separator: "/")
    }
    return RouteMatch(params: params, specificity: specificity)
  }
}
