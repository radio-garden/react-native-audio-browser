import Foundation

/// Utility for handling browser paths and contextual URLs in the media browser system.
///
/// Handles two types of special paths:
/// 1. System paths (prefixed with `/__`): root, search, and offline paths
/// 2. Contextual URLs: Embed parent context in track identifiers for playback integration
///
/// Contextual URL format: `{parentPath}?__trackId={trackIdentity}&__index={childIndex}`
/// Example: "/library/radio?__trackId=song.mp3&__index=2"
///
/// `__trackId` is the identity check; `__index` (the child's position on the
/// page at stamp time) is only a tie-breaker between surfaces that carry the
/// same identity — a stale index never selects a different track.
///
/// This allows:
/// - Playable-only tracks (tracks with `src` but no `path`) to be referenced
/// - Cache lookup to work consistently
/// - Parent context to be preserved for queue restoration
enum BrowserPathHelper {
  /// Root path for media browsing
  static let rootPath = "/__root"

  /// Search path prefix (full path is /__search?q=query)
  static let searchPathPrefix = "/__search"

  /// Offline error placeholder media ID
  static let offlinePath = "/__offline"

  /// Character set for percent-encoding query parameter keys and values.
  /// Starts from `.urlQueryAllowed` but removes characters that have special
  /// meaning in query strings (`&`, `=`, `+`) to prevent value corruption.
  private static let queryComponentAllowed: CharacterSet = {
    var cs = CharacterSet.urlQueryAllowed
    cs.remove(charactersIn: "&=+")
    return cs
  }()

  /// Query parameter name for contextual track identifiers
  private static let contextualTrackParam = "__trackId"

  /// Query parameter name for the tapped child's page position (tie-breaker)
  private static let contextualIndexParam = "__index"

  /// Check if a path is a special system path (not a regular navigation path)
  static func isSpecialPath(_ path: String) -> Bool {
    path == rootPath ||
      path.hasPrefix("\(searchPathPrefix)?")
  }

  /// Create a search path for a given query
  static func createSearchPath(_ query: String) -> String {
    let encodedQuery =
      query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
    return "\(searchPathPrefix)?q=\(encodedQuery)"
  }

  /// Checks if a path contains a contextual track identifier.
  ///
  /// - Parameter path: The URL path to check
  /// - Returns: true if the path contains the contextual track parameter
  static func isContextual(_ path: String) -> Bool {
    path.contains("?\(contextualTrackParam)=")
      || path.contains("&\(contextualTrackParam)=")
  }

  /// Strips the contextual parameters (__trackId and __index) from a contextual
  /// URL to get the parent path. If the URL is not contextual, returns it unchanged.
  ///
  /// - Parameter url: The URL to process
  /// - Returns: The URL without the contextual parameters
  ///
  /// Example: "/library/radio?__trackId=song.mp3&__index=2" → "/library/radio"
  /// Example: "/search?q=jazz&__trackId=song.mp3" → "/search?q=jazz"
  static func stripTrackId(_ url: String) -> String {
    guard isContextual(url) else {
      return url
    }

    guard var components = URLComponents(string: url) else {
      return url
    }

    // Filter out the contextual parameters
    components.queryItems = components.queryItems?.filter {
      $0.name != contextualTrackParam && $0.name != contextualIndexParam
    }

    // If no query items left, clear the query string entirely
    if components.queryItems?.isEmpty == true {
      components.queryItems = nil
    }

    return components.string ?? url
  }

  /// Builds a contextual URL by appending a track identifier — and optionally
  /// the tapped child's page position — to a parent path.
  /// Handles existing query parameters correctly.
  ///
  /// - Parameters:
  ///   - parentPath: The parent container path
  ///   - trackId: The track identity (`id` when non-blank, else `src`)
  ///   - index: The child's position on the page at stamp time (tie-breaker)
  /// - Returns: A contextual URL combining parent path, track ID, and index
  ///
  /// Example: build("/library", "song.mp3", index: 2) → "/library?__trackId=song.mp3&__index=2"
  /// Example: build("/search?q=jazz", "song.mp3") → "/search?q=jazz&__trackId=song.mp3"
  static func build(parentPath: String, trackId: String, index: Int? = nil) -> String {
    let encodedTrackId =
      trackId.addingPercentEncoding(withAllowedCharacters: queryComponentAllowed) ?? trackId
    let separator = parentPath.contains("?") ? "&" : "?"
    let indexParam = index.map { "&\(contextualIndexParam)=\($0)" } ?? ""
    return "\(parentPath)\(separator)\(contextualTrackParam)=\(encodedTrackId)\(indexParam)"
  }

  /// Extracts the track ID from a contextual URL.
  /// Returns nil if the URL is not contextual or doesn't contain the track ID parameter.
  ///
  /// - Parameter path: The contextual URL to parse
  /// - Returns: The extracted track ID, or nil if not found
  ///
  /// Example: "/library/radio?__trackId=song.mp3" → "song.mp3"
  static func extractTrackId(_ path: String) -> String? {
    guard isContextual(path) else {
      return nil
    }

    guard let components = URLComponents(string: path) else {
      return nil
    }

    return components.queryItems?.first { $0.name == contextualTrackParam }?.value
  }

  /// Extracts the stamped page index from a contextual URL, or nil when the
  /// URL is not contextual or carries no (valid, non-negative) index.
  ///
  /// Example: "/library/radio?__trackId=song.mp3&__index=2" → 2
  static func extractIndex(_ path: String) -> Int? {
    guard isContextual(path),
          let components = URLComponents(string: path),
          let raw = components.queryItems?.first(where: { $0.name == contextualIndexParam })?.value,
          let index = Int(raw), index >= 0
    else {
      return nil
    }
    return index
  }

  /// Appends query parameters to a URL, handling `?` vs `&` separator and percent-encoding.
  ///
  /// - Parameters:
  ///   - query: Dictionary of query parameter key-value pairs
  ///   - url: The URL to append parameters to
  /// - Returns: The URL with query parameters appended
  ///
  /// Examples:
  /// - appendQuery(["q": "jazz"], to: "/search") → "/search?q=jazz"
  /// - appendQuery(["page": "2"], to: "/items?sort=new") → "/items?sort=new&page=2"
  static func appendQuery(_ query: [String: String], to url: String) -> String {
    guard !query.isEmpty else { return url }

    let queryString = query.keys.sorted()
      .map { key in
        let value = query[key]!
        let encodedKey = key.addingPercentEncoding(withAllowedCharacters: queryComponentAllowed) ?? key
        let encodedValue = value.addingPercentEncoding(withAllowedCharacters: queryComponentAllowed) ?? value
        return "\(encodedKey)=\(encodedValue)"
      }
      .joined(separator: "&")
    let separator = url.contains("?") ? "&" : "?"
    return "\(url)\(separator)\(queryString)"
  }

  /// Combines a base URL with a path, ensuring proper slash handling.
  ///
  /// - Parameters:
  ///   - baseUrl: The base URL (can be nil)
  ///   - path: The path to append
  /// - Returns: The combined URL with proper slash handling
  ///
  /// Examples:
  /// - buildUrl("http://example.com", "api/test") → "http://example.com/api/test"
  /// - buildUrl("http://example.com/", "/api/test") → "http://example.com/api/test"
  /// - buildUrl(nil, "/api/test") → "/api/test"
  /// - buildUrl(nil, "http://full.url") → "http://full.url"
  static func buildUrl(baseUrl: String?, path: String) -> String {
    // If path is already a full URL, return it as-is
    if path.hasPrefix("http://") || path.hasPrefix("https://") {
      return path
    }

    // If no baseUrl, return path as-is
    guard let baseUrl else {
      return path
    }

    // Strip trailing slashes from the base and leading slashes from the path so
    // they join with exactly one separator.
    var normalizedBase = baseUrl
    while normalizedBase.hasSuffix("/") {
      normalizedBase.removeLast()
    }

    var normalizedPath = path
    while normalizedPath.hasPrefix("/") {
      normalizedPath.removeFirst()
    }

    // Empty path → the base IS the full URL (e.g. a search endpoint whose
    // baseUrl already includes the path); don't leave a dangling trailing slash.
    if normalizedPath.isEmpty {
      return normalizedBase
    }

    return "\(normalizedBase)/\(normalizedPath)"
  }
}
