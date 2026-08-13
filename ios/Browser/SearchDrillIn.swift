import Foundation

/// A search result that may or may not be directly playable. `src` is the
/// playable stream; `path` is a browsable container path. Abstracting these two
/// fields keeps the drill-in decision free of the Nitro `Track` type so it can
/// be unit-tested.
protocol PlayableSearchItem {
  var src: String? { get }
  var path: String? { get }
}

enum SearchDrillIn {
  /// Picks the playable items for a "play «X»" voice search.
  ///
  /// If the first result is browsable-only (a `path` container with no `src` — a
  /// place/genre page), drills into it via `resolveChildren` and uses its
  /// playable children, so we play the first station *inside* the page rather
  /// than skipping the page for a later inline station. Falls back to the flat
  /// playable results when the first result is already playable or the drill-in
  /// yields nothing. Returns nil when nothing is playable.
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

// The real Nitro `Track` already carries `src`/`path`; conform it for the app
// build. (The testable target builds without NitroModules and uses a stub item.)
#if canImport(NitroModules)
  extension Track: PlayableSearchItem {}
#endif
