import Testing

@testable import AudioBrowserTestable

private func makeResolvedTrack(
  url: String = "/test",
  title: String = "Test Track"
) -> ResolvedTrack {
  ResolvedTrack(
    url: url,
    children: nil,
    carPlaySiriListButton: nil,
    src: nil,
    artwork: nil,
    artworkSource: nil,
    artworkCarPlayTinted: nil,
    title: title,
    subtitle: nil,
    artist: nil,
    album: nil,
    description: nil,
    genre: nil,
    duration: nil,
    style: nil,
    childrenStyle: nil,
    favorited: nil,
    groupTitle: nil,
    live: nil,
    imageRow: nil
  )
}

private func makeFullResolvedTrack() -> ResolvedTrack {
  ResolvedTrack(
    url: "/original",
    children: [Track(id: "t1", url: "/t1")],
    carPlaySiriListButton: .top,
    src: "src.mp3",
    artwork: "art.jpg",
    artworkSource: ImageSource(uri: "resolved-art.jpg"),
    artworkCarPlayTinted: true,
    title: "Original Title",
    subtitle: "Original Subtitle",
    artist: "Original Artist",
    album: "Original Album",
    description: "Original Description",
    genre: "Rock",
    duration: 180.0,
    style: .list,
    childrenStyle: .grid,
    favorited: true,
    groupTitle: "Group A",
    live: false,
    imageRow: [ImageRowItem(title: "Row 1")]
  )
}

// MARK: - no-op copy

@Test func copyingWithNoArgsPreservesAllFields() {
  let original = makeFullResolvedTrack()
  let copy = original.copying()
  #expect(copy == original)
}

// MARK: - non-optional fields (url, title)

@Test func copyingOverridesUrl() {
  let original = makeResolvedTrack(url: "/old")
  let copy = original.copying(url: "/new")
  #expect(copy.url == "/new")
  #expect(copy.title == "Test Track")
}

@Test func copyingOverridesTitle() {
  let original = makeResolvedTrack(title: "Old")
  let copy = original.copying(title: "New")
  #expect(copy.title == "New")
  #expect(copy.url == "/test")
}

// MARK: - optional fields: set value

@Test func copyingOverridesChildren() {
  let original = makeResolvedTrack()
  let children = [Track(id: "c1", url: "/c1"), Track(id: "c2", url: "/c2")]
  let copy = original.copying(children: children)
  #expect(copy.children?.count == 2)
  #expect(copy.children?[0].id == "c1")
}

@Test func copyingOverridesSrc() {
  let original = makeResolvedTrack()
  let copy = original.copying(src: "new-src.mp3")
  #expect(copy.src == "new-src.mp3")
}

@Test func copyingOverridesArtwork() {
  let original = makeResolvedTrack()
  let copy = original.copying(artwork: "new-art.jpg")
  #expect(copy.artwork == "new-art.jpg")
}

@Test func copyingOverridesArtworkSource() {
  let original = makeResolvedTrack()
  let source = ImageSource(uri: "new-source.jpg")
  let copy = original.copying(artworkSource: source)
  #expect(copy.artworkSource == source)
}

@Test func copyingOverridesSubtitle() {
  let original = makeResolvedTrack()
  let copy = original.copying(subtitle: "New Subtitle")
  #expect(copy.subtitle == "New Subtitle")
}

@Test func copyingOverridesArtist() {
  let original = makeResolvedTrack()
  let copy = original.copying(artist: "New Artist")
  #expect(copy.artist == "New Artist")
}

@Test func copyingOverridesDuration() {
  let original = makeResolvedTrack()
  let copy = original.copying(duration: 300.0)
  #expect(copy.duration == 300.0)
}

@Test func copyingOverridesStyle() {
  let original = makeResolvedTrack()
  let copy = original.copying(style: .grid)
  #expect(copy.style == .grid)
}

@Test func copyingOverridesFavorited() {
  let original = makeResolvedTrack()
  let copy = original.copying(favorited: true)
  #expect(copy.favorited == true)
}

@Test func copyingOverridesLive() {
  let original = makeResolvedTrack()
  let copy = original.copying(live: true)
  #expect(copy.live == true)
}

@Test func copyingOverridesCarPlaySiriListButton() {
  let original = makeResolvedTrack()
  let copy = original.copying(carPlaySiriListButton: .bottom)
  #expect(copy.carPlaySiriListButton == .bottom)
}

@Test func copyingOverridesImageRow() {
  let original = makeResolvedTrack()
  let items = [ImageRowItem(title: "Item 1"), ImageRowItem(title: "Item 2")]
  let copy = original.copying(imageRow: items)
  #expect(copy.imageRow == items)
}

// MARK: - optional fields: set to nil via .some(nil)

@Test func copyingClearsSrcToNil() {
  let original = makeFullResolvedTrack()
  #expect(original.src != nil)
  let copy = original.copying(src: .some(nil))
  #expect(copy.src == nil)
}

@Test func copyingClearsChildrenToNil() {
  let original = makeFullResolvedTrack()
  #expect(original.children != nil)
  let copy = original.copying(children: .some(nil))
  #expect(copy.children == nil)
}

@Test func copyingClearsArtworkToNil() {
  let original = makeFullResolvedTrack()
  #expect(original.artwork != nil)
  let copy = original.copying(artwork: .some(nil))
  #expect(copy.artwork == nil)
}

@Test func copyingClearsFavoritedToNil() {
  let original = makeFullResolvedTrack()
  #expect(original.favorited != nil)
  let copy = original.copying(favorited: .some(nil))
  #expect(copy.favorited == nil)
}

@Test func copyingClearsDurationToNil() {
  let original = makeFullResolvedTrack()
  #expect(original.duration != nil)
  let copy = original.copying(duration: .some(nil))
  #expect(copy.duration == nil)
}

// MARK: - multiple fields at once

@Test func copyingOverridesMultipleFields() {
  let original = makeResolvedTrack()
  let copy = original.copying(
    url: "/updated",
    title: "Updated Title",
    artist: "New Artist",
    duration: 240.0,
    favorited: true
  )
  #expect(copy.url == "/updated")
  #expect(copy.title == "Updated Title")
  #expect(copy.artist == "New Artist")
  #expect(copy.duration == 240.0)
  #expect(copy.favorited == true)
  // Unchanged fields
  #expect(copy.src == nil)
  #expect(copy.artwork == nil)
  #expect(copy.children == nil)
}

// MARK: - preserves unmodified fields

@Test func copyingPreservesUnmodifiedFields() {
  let original = makeFullResolvedTrack()
  let copy = original.copying(title: "Changed Only Title")
  #expect(copy.title == "Changed Only Title")
  // All other fields unchanged
  #expect(copy.url == "/original")
  #expect(copy.children?.count == 1)
  #expect(copy.carPlaySiriListButton == .top)
  #expect(copy.src == "src.mp3")
  #expect(copy.artwork == "art.jpg")
  #expect(copy.artworkSource == ImageSource(uri: "resolved-art.jpg"))
  #expect(copy.artworkCarPlayTinted == true)
  #expect(copy.subtitle == "Original Subtitle")
  #expect(copy.artist == "Original Artist")
  #expect(copy.album == "Original Album")
  #expect(copy.description == "Original Description")
  #expect(copy.genre == "Rock")
  #expect(copy.duration == 180.0)
  #expect(copy.style == .list)
  #expect(copy.childrenStyle == .grid)
  #expect(copy.favorited == true)
  #expect(copy.groupTitle == "Group A")
  #expect(copy.live == false)
  #expect(copy.imageRow == [ImageRowItem(title: "Row 1")])
}
