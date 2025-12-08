import Foundation

/// Utility for handling browser paths and contextual URLs in the media browser system.
///
/// Handles two types of special paths:
/// 1. System paths (prefixed with `/__`): Root, recent, and search paths
/// 2. Contextual URLs: Embed parent context in track identifiers for playback integration
///
/// Contextual URL format: `{parentPath}?__trackId={trackSrc}`
/// Example: "/library/radio?__trackId=song.mp3"
///
/// This allows:
/// - Playable-only tracks (tracks with `src` but no `url`) to be referenced
/// - Cache lookup to work consistently
/// - Parent context to be preserved for queue restoration
enum BrowserPathHelper {
  /// Root path for media browsing
  static let rootPath = "/__root"

  /// Recent media path for playback resumption
  static let recentPath = "/__recent"

  /// Search path prefix (full path is /__search?q=query)
  static let searchPathPrefix = "/__search"

  /// Offline error placeholder media ID
  static let offlinePath = "/__offline"

  /// Query parameter name for contextual track identifiers
  private static let contextualTrackParam = "__trackId"

  /// Check if a path is a special system path (not a regular navigation path)
  static func isSpecialPath(_ path: String) -> Bool {
    return path == rootPath ||
      path == recentPath ||
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
    return path.contains("?\(contextualTrackParam)=")
      || path.contains("&\(contextualTrackParam)=")
  }

  /// Strips the __trackId parameter from a contextual URL to get the parent path.
  /// If the URL is not contextual, returns it unchanged.
  ///
  /// - Parameter url: The URL to process
  /// - Returns: The URL without the __trackId parameter
  ///
  /// Example: "/library/radio?__trackId=song.mp3" → "/library/radio"
  /// Example: "/search?q=jazz&__trackId=song.mp3" → "/search?q=jazz"
  static func stripTrackId(_ url: String) -> String {
    guard isContextual(url) else {
      return url
    }

    guard var components = URLComponents(string: url) else {
      return url
    }

    // Filter out __trackId parameter
    components.queryItems = components.queryItems?.filter { $0.name != contextualTrackParam }

    // If no query items left, clear the query string entirely
    if components.queryItems?.isEmpty == true {
      components.queryItems = nil
    }

    return components.string ?? url
  }

  /// Builds a contextual URL by appending a track identifier to a parent path.
  /// Handles existing query parameters correctly.
  ///
  /// - Parameters:
  ///   - parentPath: The parent container path
  ///   - trackId: The track identifier (typically the `src` value)
  /// - Returns: A contextual URL combining parent path and track ID
  ///
  /// Example: build("/library", "song.mp3") → "/library?__trackId=song.mp3"
  /// Example: build("/search?q=jazz", "song.mp3") → "/search?q=jazz&__trackId=song.mp3"
  static func build(parentPath: String, trackId: String) -> String {
    let encodedTrackId =
      trackId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? trackId
    let separator = parentPath.contains("?") ? "&" : "?"
    return "\(parentPath)\(separator)\(contextualTrackParam)=\(encodedTrackId)"
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
    guard let baseUrl = baseUrl else {
      return path
    }

    // Ensure baseUrl ends with / and path doesn't start with /
    var normalizedBase = baseUrl
    while normalizedBase.hasSuffix("/") {
      normalizedBase.removeLast()
    }
    normalizedBase += "/"

    var normalizedPath = path
    while normalizedPath.hasPrefix("/") {
      normalizedPath.removeFirst()
    }

    return "\(normalizedBase)\(normalizedPath)"
  }
}
