import Testing

@testable import AudioBrowserTestable

struct TabBarEntriesTests {
  private func tab(
    _ title: String, path: String, artwork: String? = nil,
    artworkSource: ImageSource? = nil, artist: String? = nil,
  ) -> Track {
    Track(
      id: path, path: path, artwork: artwork.map { .first($0) },
      artworkSource: artworkSource, title: title, artist: artist,
    )
  }

  @Test func identicalListsAreSame() {
    let tabs = [tab("Explore", path: "/explore"), tab("Browse", path: "/browse")]
    #expect(TabBarEntries.same(tabs, tabs))
  }

  @Test func titleChangeDiffers() {
    #expect(
      !TabBarEntries.same(
        [tab("Explore", path: "/explore")],
        [tab("Verkennen", path: "/explore")],
      ))
  }

  @Test func pathChangeDiffers() {
    #expect(
      !TabBarEntries.same(
        [tab("Explore", path: "/explore")],
        [tab("Explore", path: "/discover")],
      ))
  }

  @Test func artworkChangeDiffers() {
    #expect(
      !TabBarEntries.same(
        [tab("Explore", path: "/explore", artwork: "sf:globe")],
        [tab("Explore", path: "/explore", artwork: "sf:map")],
      ))
  }

  @Test func artworkSourceUriChangeDiffers() {
    #expect(
      !TabBarEntries.same(
        [tab("Explore", path: "/explore", artworkSource: ImageSource(uri: "https://a/1.png"))],
        [tab("Explore", path: "/explore", artworkSource: ImageSource(uri: "https://a/2.png"))],
      ))
  }

  @Test func countChangeDiffers() {
    let tabs = [tab("Explore", path: "/explore")]
    #expect(!TabBarEntries.same(tabs, tabs + [tab("Browse", path: "/browse")]))
  }

  @Test func nilOldDiffers() {
    #expect(!TabBarEntries.same(nil, [tab("Explore", path: "/explore")]))
  }

  @Test func nonRenderedFieldChangeIsSame() {
    #expect(
      TabBarEntries.same(
        [tab("Explore", path: "/explore", artist: "a")],
        [tab("Explore", path: "/explore", artist: "b")],
      ))
  }

  @Test func tabsThatGoAwayEmitAnEmptyList() {
    #expect(TabBarEntries.change(from: [tab("Explore", path: "/explore")], to: nil)?.isEmpty == true)
  }

  @Test func noTabsBeforeOrAfterEmitsNothing() {
    #expect(TabBarEntries.change(from: nil, to: nil) == nil)
  }

  @Test func newTabsEmitThem() {
    let tabs = [tab("Explore", path: "/explore")]
    #expect(TabBarEntries.change(from: nil, to: tabs)?.map(\.path) == ["/explore"])
    #expect(TabBarEntries.change(from: [tab("Browse", path: "/browse")], to: tabs)?.map(\.path) == ["/explore"])
  }

  @Test func sameEntriesEmitNothing() {
    let tabs = [tab("Explore", path: "/explore")]
    #expect(TabBarEntries.change(from: tabs, to: tabs) == nil)
  }
}
