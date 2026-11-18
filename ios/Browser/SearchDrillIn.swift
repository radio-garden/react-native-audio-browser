import Foundation

/// A search result: `src` plays it, `path` browses it. A protocol rather than `Track`, so the test
/// target builds without NitroModules.
protocol PlayableSearchItem {
  var src: String? { get }
  var path: String? { get }
}

enum SearchDrillIn {
  /// The items a voice "play X" search plays, or nil when none is playable. When the first result
  /// is browsable but not playable, its playable children win over later playable results; when it
  /// has none, the playable results play.
  static func playable<Item: PlayableSearchItem>(
    from children: [Item],
    resolveChildren: sending (String) async throws -> [Item],
  ) async throws -> [Item]? {
    guard !children.isEmpty else { return nil }

    let candidates: [Item]
    if let first = children.first, first.src == nil, let path = first.path {
      let drilled = try await resolveChildren(path).filter { $0.src != nil }
      candidates = drilled.isEmpty ? children : drilled
    } else {
      candidates = children
    }

    let playable = candidates.filter { $0.src != nil }
    return playable.isEmpty ? nil : playable
  }
}

#if canImport(NitroModules)
  extension Track: PlayableSearchItem {}
#endif
