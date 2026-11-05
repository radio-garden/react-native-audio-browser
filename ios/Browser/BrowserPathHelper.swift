import Foundation
#if canImport(NitroModules)
  import NitroModules
#endif

/// Search paths (`/__search?q=…`) and contextual paths, which address a child of a page:
/// `{parentPath}?__trackId={identity}&__index={position}`. `__trackId` decides the track;
/// `__index` only breaks ties between children with the same identity, so a stale index never
/// selects a different track.
enum BrowserPathHelper {
  static let searchPathPrefix = "/__search"

  private static let queryComponentAllowed: CharacterSet = {
    var cs = CharacterSet.urlQueryAllowed
    cs.remove(charactersIn: "&=+")
    return cs
  }()

  private static let contextualTrackParam = "__trackId"

  private static let contextualIndexParam = "__index"

  static func isSearchPath(_ path: String) -> Bool {
    path.hasPrefix("\(searchPathPrefix)?")
  }

  static func createSearchPath(_ query: String) -> String {
    let encodedQuery =
      query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
    return "\(searchPathPrefix)?q=\(encodedQuery)"
  }

  static func isContextual(_ path: String) -> Bool {
    path.contains("?\(contextualTrackParam)=")
      || path.contains("&\(contextualTrackParam)=")
  }

  /// Strips both `__trackId` and `__index`; `url` unchanged when it is not contextual.
  static func stripTrackId(_ url: String) -> String {
    guard isContextual(url) else {
      return url
    }

    guard var components = URLComponents(string: url) else {
      return url
    }

    components.queryItems = components.queryItems?.filter {
      $0.name != contextualTrackParam && $0.name != contextualIndexParam
    }

    if components.queryItems?.isEmpty == true {
      components.queryItems = nil
    }

    return components.string ?? url
  }

  /// `parentPath` with `trackId` (the track's identity) and, when given, its page position
  /// appended; keeps any query `parentPath` already has.
  static func build(parentPath: String, trackId: String, index: Int? = nil) -> String {
    let encodedTrackId =
      trackId.addingPercentEncoding(withAllowedCharacters: queryComponentAllowed) ?? trackId
    let separator = parentPath.contains("?") ? "&" : "?"
    let indexParam = index.map { "&\(contextualIndexParam)=\($0)" } ?? ""
    return "\(parentPath)\(separator)\(contextualTrackParam)=\(encodedTrackId)\(indexParam)"
  }

  static func extractTrackId(_ path: String) -> String? {
    guard isContextual(path) else {
      return nil
    }

    guard let components = URLComponents(string: path) else {
      return nil
    }

    return components.queryItems?.first { $0.name == contextualTrackParam }?.value
  }

  /// Nil when the path is not contextual or its index is missing or negative.
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

  /// Percent-encodes keys and values, so pass them unencoded.
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

  /// `path` joined to `baseUrl` with one slash; `path` alone when it is a full http(s) URL or
  /// there is no base, and the base alone when `path` is empty or only slashes.
  static func buildUrl(baseUrl: String?, path: String) -> String {
    if path.hasPrefix("http://") || path.hasPrefix("https://") {
      return path
    }

    guard let baseUrl else {
      return path
    }

    var normalizedBase = baseUrl
    while normalizedBase.hasSuffix("/") {
      normalizedBase.removeLast()
    }

    var normalizedPath = path
    while normalizedPath.hasPrefix("/") {
      normalizedPath.removeFirst()
    }

    if normalizedPath.isEmpty {
      return normalizedBase
    }

    return "\(normalizedBase)/\(normalizedPath)"
  }
}

extension BrowserPathHelper {
  /// `sections` with a contextual path on every track that has a `src` and no `path`, its index
  /// counted across all sections. Throws on a track with neither.
  static func stampingContextualPaths(_ sections: [Section], parentPath: String) throws -> [Section] {
    var flatIndex = 0
    return try sections.map { section in
      try section.copying(children: section.children.map { track in
        defer { flatIndex += 1 }
        if track.path != nil { return track }
        guard track.src != nil, let identity = track.identity else {
          throw BrowserError.invalidConfiguration(
            "Track must have either 'path' or 'src' for stable identification: \(track.title)",
          )
        }
        return track.copying(path: build(parentPath: parentPath, trackId: identity, index: flatIndex))
      })
    }
  }
}
