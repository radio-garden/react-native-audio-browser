import Testing

@testable import AudioBrowserTestable

private struct Item: PlayableSearchItem {
  let src: String?
  let path: String?
}

@Suite("SearchDrillIn.playable")
struct SearchDrillInTests {
  /// A resolver that fails the test if the drill-in path calls it.
  private func unusedResolver(_ path: String) async throws -> [Item] {
    Issue.record("resolveChildren should not have been called (path: \(path))")
    return []
  }

  @Test func noResults_returnsNil() async throws {
    let result = try await SearchDrillIn.playable(from: [Item](), resolveChildren: unusedResolver)
    #expect(result == nil)
  }

  @Test func firstPlayable_returnsFlatPlayable_inOrder_withoutResolving() async throws {
    let items = [
      Item(src: "/a.mp3", path: nil),
      Item(src: nil, path: "/page/x"), // browsable, filtered out
      Item(src: "/b.mp3", path: nil),
    ]
    let result = try await SearchDrillIn.playable(from: items, resolveChildren: unusedResolver)
    #expect(result?.compactMap(\.src) == ["/a.mp3", "/b.mp3"])
  }

  @Test func firstBrowsable_drillsIntoIt_andPlaysItsChildren() async throws {
    let items = [
      Item(src: nil, path: "/page/place"), // browsable first result
      Item(src: "/later.mp3", path: nil), // would win without drill-in
    ]
    let result = try await SearchDrillIn.playable(from: items) { path in
      #expect(path == "/page/place")
      return [Item(src: "/station1.mp3", path: nil), Item(src: "/station2.mp3", path: nil)]
    }
    #expect(result?.compactMap(\.src) == ["/station1.mp3", "/station2.mp3"])
  }

  @Test func browsableDrillYieldsNothing_fallsBackToFlat() async throws {
    let items = [
      Item(src: nil, path: "/page/empty"),
      Item(src: "/fallback.mp3", path: nil),
    ]
    let result = try await SearchDrillIn.playable(from: items) { _ in
      [Item(src: nil, path: "/page/nested")] // nothing playable inside
    }
    #expect(result?.compactMap(\.src) == ["/fallback.mp3"])
  }

  @Test func nothingPlayableAnywhere_returnsNil() async throws {
    let items = [Item(src: nil, path: "/page/a"), Item(src: nil, path: "/page/b")]
    let result = try await SearchDrillIn.playable(from: items) { _ in
      [Item(src: nil, path: "/page/nested")]
    }
    #expect(result == nil)
  }
}
