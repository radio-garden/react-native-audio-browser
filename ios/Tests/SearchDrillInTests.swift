import Testing

@testable import AudioBrowserTestable

private struct Item: PlayableSearchItem {
  let src: String?
  let url: String?
}

@Suite("SearchDrillIn.playable")
struct SearchDrillInTests {
  /// A resolver that fails the test if the drill-in path calls it.
  private func unusedResolver(_ url: String) async throws -> [Item] {
    Issue.record("resolveChildren should not have been called (url: \(url))")
    return []
  }

  @Test func noResults_returnsNil() async throws {
    let result = try await SearchDrillIn.playable(from: [Item](), resolveChildren: unusedResolver)
    #expect(result == nil)
  }

  @Test func firstPlayable_returnsFlatPlayable_inOrder_withoutResolving() async throws {
    let items = [
      Item(src: "/a.mp3", url: nil),
      Item(src: nil, url: "/page/x"), // browsable, filtered out
      Item(src: "/b.mp3", url: nil),
    ]
    let result = try await SearchDrillIn.playable(from: items, resolveChildren: unusedResolver)
    #expect(result?.compactMap(\.src) == ["/a.mp3", "/b.mp3"])
  }

  @Test func firstBrowsable_drillsIntoIt_andPlaysItsChildren() async throws {
    let items = [
      Item(src: nil, url: "/page/place"), // browsable first result
      Item(src: "/later.mp3", url: nil), // would win without drill-in
    ]
    let result = try await SearchDrillIn.playable(from: items) { url in
      #expect(url == "/page/place")
      return [Item(src: "/station1.mp3", url: nil), Item(src: "/station2.mp3", url: nil)]
    }
    #expect(result?.compactMap(\.src) == ["/station1.mp3", "/station2.mp3"])
  }

  @Test func browsableDrillYieldsNothing_fallsBackToFlat() async throws {
    let items = [
      Item(src: nil, url: "/page/empty"),
      Item(src: "/fallback.mp3", url: nil),
    ]
    let result = try await SearchDrillIn.playable(from: items) { _ in
      [Item(src: nil, url: "/page/nested")] // nothing playable inside
    }
    #expect(result?.compactMap(\.src) == ["/fallback.mp3"])
  }

  @Test func nothingPlayableAnywhere_returnsNil() async throws {
    let items = [Item(src: nil, url: "/page/a"), Item(src: nil, url: "/page/b")]
    let result = try await SearchDrillIn.playable(from: items) { _ in
      [Item(src: nil, url: "/page/nested")]
    }
    #expect(result == nil)
  }
}
