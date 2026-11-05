#if canImport(NitroModules)
  import NitroModules
#endif

extension SearchParams {
  /// `q` plus whichever filters are set, matching Android and web. Also the search's cache
  /// identity (`cacheKey`), so a field added here distinguishes both.
  var queryParams: [String: String] {
    var query: [String: String] = ["q": query]
    if let mode { query["mode"] = mode.stringValue }
    if reference == .my { query["reference"] = "my" }
    if let genre { query["genre"] = genre }
    if let artist { query["artist"] = artist }
    if let album { query["album"] = album }
    if let title { query["title"] = title }
    if let playlist { query["playlist"] = playlist }
    return query
  }

  /// `queryParams` sorted so key order is stable, and joined with `\u{1}` rather than a
  /// printable separator so two different param sets don't serialize alike.
  var cacheKey: String {
    queryParams
      .sorted { $0.key < $1.key }
      .map { "\($0.key)=\($0.value)" }
      .joined(separator: "\u{1}")
  }
}
