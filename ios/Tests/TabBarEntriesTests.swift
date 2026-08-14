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
    // Only fields the tab bar renders are compared; other track fields may
    // drift without churning the tab bar.
    #expect(
      TabBarEntries.same(
        [tab("Explore", path: "/explore", artist: "a")],
        [tab("Explore", path: "/explore", artist: "b")],
      ))
  }
}
