import Foundation

/// A search result: `src` plays it, `path` browses it. Keeps the drill-in free of the Nitro
/// `Track` so it can be unit-tested.
protocol PlayableSearchItem {
  var src: String? { get }
  var path: String? { get }
}

enum SearchDrillIn {
  /// The items a "play «X»" voice search plays, or nil when none is playable.
  ///
  /// Only the first result is drilled into: when it is browsable but not playable, its playable
  /// children win over later playable results, so the first station inside a place or genre page
  /// plays. Falls back to the playable results when the drill-in yields none.
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

// The test target builds without NitroModules and uses its own item.
#if canImport(NitroModules)
  extension Track: PlayableSearchItem {}
#endif
