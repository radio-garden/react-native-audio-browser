import Testing

@testable import AudioBrowserTestable

struct TabBarEntriesTests {
  private func tab(
    _ title: String, url: String, artwork: String? = nil,
    artworkSource: ImageSource? = nil, artist: String? = nil
  ) -> Track {
    Track(
      id: url, url: url, title: title, artist: artist, artwork: artwork.map { .first($0) },
      artworkSource: artworkSource
    )
  }

  @Test func identicalListsAreSame() {
    let tabs = [tab("Explore", url: "/explore"), tab("Browse", url: "/browse")]
    #expect(TabBarEntries.same(tabs, tabs))
  }

  @Test func titleChangeDiffers() {
    #expect(
      !TabBarEntries.same(
        [tab("Explore", url: "/explore")],
        [tab("Verkennen", url: "/explore")]
      ))
  }

  @Test func urlChangeDiffers() {
    #expect(
      !TabBarEntries.same(
        [tab("Explore", url: "/explore")],
        [tab("Explore", url: "/discover")]
      ))
  }

  @Test func artworkChangeDiffers() {
    #expect(
      !TabBarEntries.same(
        [tab("Explore", url: "/explore", artwork: "sf:globe")],
        [tab("Explore", url: "/explore", artwork: "sf:map")]
      ))
  }

  @Test func artworkSourceUriChangeDiffers() {
    #expect(
      !TabBarEntries.same(
        [tab("Explore", url: "/explore", artworkSource: ImageSource(uri: "https://a/1.png"))],
        [tab("Explore", url: "/explore", artworkSource: ImageSource(uri: "https://a/2.png"))]
      ))
  }

  @Test func countChangeDiffers() {
    let tabs = [tab("Explore", url: "/explore")]
    #expect(!TabBarEntries.same(tabs, tabs + [tab("Browse", url: "/browse")]))
  }

  @Test func nilOldDiffers() {
    #expect(!TabBarEntries.same(nil, [tab("Explore", url: "/explore")]))
  }

  @Test func nonRenderedFieldChangeIsSame() {
    // Only fields the tab bar renders are compared; other track fields may
    // drift without churning the tab bar.
    #expect(
      TabBarEntries.same(
        [tab("Explore", url: "/explore", artist: "a")],
        [tab("Explore", url: "/explore", artist: "b")]
      ))
  }
}
