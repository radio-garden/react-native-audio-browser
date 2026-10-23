#if canImport(NitroModules)
  import NitroModules
#endif

extension SearchParams {
  /// The query params this search sends: `q` plus whichever filters are set, as on Android and
  /// web. Also the search's cache identity, so a field added here distinguishes both.
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

  /// The cache identity: `queryParams`, sorted so key order is stable and `\u{1}`-delimited so
  /// two param sets cannot serialize alike.
  var cacheKey: String {
    queryParams
      .sorted { $0.key < $1.key }
      .map { "\($0.key)=\($0.value)" }
      .joined(separator: "\u{1}")
  }
}
