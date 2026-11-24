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
      query.addingPercentEncoding(withAllowedCharacters: queryComponentAllowed) ?? query
    return "\(searchPathPrefix)?q=\(encodedQuery)"
  }

  static func isContextual(_ path: String) -> Bool {
    path.contains("?\(contextualTrackParam)=")
      || path.contains("&\(contextualTrackParam)=")
  }

  /// Strips both `__trackId` and `__index` and leaves every other character as it is, so the
  /// result equals the parent path the contextual path was built on; `url` unchanged when it is
  /// not contextual.
  static func stripTrackId(_ url: String) -> String {
    guard isContextual(url), let query = splitQuery(url) else {
      return url
    }

    let kept = query.pairs.filter {
      !$0.hasPrefix("\(contextualTrackParam)=") && !$0.hasPrefix("\(contextualIndexParam)=")
    }
    return kept.isEmpty ? String(query.head) : "\(query.head)?\(kept.joined(separator: "&"))"
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
    contextualValue(contextualTrackParam, in: path)
  }

  /// Nil when the path is not contextual or its index is missing or negative.
  static func extractIndex(_ path: String) -> Int? {
    guard let raw = contextualValue(contextualIndexParam, in: path), let index = Int(raw), index >= 0 else {
      return nil
    }
    return index
  }

  /// `path` cut at its first `?`: what precedes it, and the `name=value` pairs after it as written.
  private static func splitQuery(_ path: String) -> (head: Substring, pairs: [Substring])? {
    guard let mark = path.firstIndex(of: "?") else {
      return nil
    }
    let pairs = path[path.index(after: mark)...].split(separator: "&", omittingEmptySubsequences: false)
    return (path[..<mark], pairs)
  }

  /// The percent-decoded value of `name` in a contextual path; nil when `path` is not contextual.
  private static func contextualValue(_ name: String, in path: String) -> String? {
    let prefix = "\(name)="
    guard isContextual(path),
          let pair = splitQuery(path)?.pairs.first(where: { $0.hasPrefix(prefix) })
    else {
      return nil
    }
    let raw = pair.dropFirst(prefix.count)
    return raw.removingPercentEncoding ?? String(raw)
  }

  /// Percent-encodes keys and values, so pass them unencoded. A `data:` URL comes back unchanged:
  /// what follows its comma is the payload.
  static func appendQuery(_ query: [String: String], to url: String) -> String {
    guard !query.isEmpty, url.prefix(5).lowercased() != "data:" else { return url }

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

  /// `path` joined to `baseUrl` with one slash; `path` alone when it has a scheme (`file:`,
  /// `data:`, `HTTPS:`, …), starts with `//` or there is no base, and the base alone when `path`
  /// is empty or only slashes.
  static func buildUrl(baseUrl: String?, path: String) -> String {
    guard let baseUrl, !isAbsoluteUrl(path) else {
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

  private static func isAbsoluteUrl(_ url: String) -> Bool {
    url.range(of: "^([A-Za-z][A-Za-z0-9+.-]*:|//)", options: .regularExpression) != nil
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
