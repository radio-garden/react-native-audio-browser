import Testing

@testable import AudioBrowserTestable

private func makeResolvedTrack(
  path: String = "/test",
  title: String = "Test Track",
) -> ResolvedTrack {
  ResolvedTrack(
    path: path,
    style: nil,
    sections: nil,
    children: nil,
    carPlaySiriListButton: nil,
    src: nil,
    artwork: nil,
    artworkSource: nil,
    title: title,
    subtitle: nil,
    artist: nil,
    album: nil,
    description: nil,
    genre: nil,
    duration: nil,
    disabled: nil,
    favorited: nil,
    live: nil,
  )
}

private func makeFullResolvedTrack() -> ResolvedTrack {
  ResolvedTrack(
    path: "/original",
    style: SectionStyle(gridWrap: false, display: .grid, artworkRendering: .stencil),
    sections: [Section(title: "Group A", subtitle: nil, style: nil, path: nil, children: [])],
    children: [Track(id: "t1", path: "/t1")],
    carPlaySiriListButton: .top,
    id: "original-id",
    src: "src.mp3",
    artwork: .first("art.jpg"),
    artworkSource: ImageSource(uri: "resolved-art.jpg"),
    title: "Original Title",
    subtitle: "Original Subtitle",
    artist: "Original Artist",
    album: "Original Album",
    description: "Original Description",
    genre: "Rock",
    duration: 180.0,
    disabled: false,
    favorited: true,
    live: false,
  )
}

// MARK: - no-op copy

@Test func copyingWithNoArgsPreservesAllFields() {
  let original = makeFullResolvedTrack()
  let copy = original.copying()
  #expect(copy == original)
}

// MARK: - non-optional fields (path, title)

@Test func copyingOverridesPath() {
  let original = makeResolvedTrack(path: "/old")
  let copy = original.copying(path: "/new")
  #expect(copy.path == "/new")
  #expect(copy.title == "Test Track")
}

@Test func copyingOverridesTitle() {
  let original = makeResolvedTrack(title: "Old")
  let copy = original.copying(title: "New")
  #expect(copy.title == "New")
  #expect(copy.path == "/test")
}

// MARK: - optional fields: set value

@Test func copyingOverridesChildren() {
  let original = makeResolvedTrack()
  let children = [Track(id: "c1", path: "/c1"), Track(id: "c2", path: "/c2")]
  let copy = original.copying(children: children)
  #expect(copy.children?.count == 2)
  #expect(copy.children?[0].id == "c1")
}

@Test func copyingOverridesSrc() {
  let original = makeResolvedTrack()
  let copy = original.copying(src: "new-src.mp3")
  #expect(copy.src == "new-src.mp3")
}

@Test func copyingOverridesId() {
  let original = makeResolvedTrack()
  let copy = original.copying(id: "new-id")
  #expect(copy.id == "new-id")
}

@Test func copyingOverridesArtwork() {
  let original = makeResolvedTrack()
  let copy = original.copying(artwork: .some(.first("new-art.jpg")))
  #expect(copy.artwork?.url == "new-art.jpg")
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
  let copy = original.copying(style: SectionStyle(gridWrap: nil, display: .grid, artworkRendering: nil))
  #expect(copy.style?.display == .grid)
}

@Test func copyingOverridesDisabled() {
  let original = makeResolvedTrack()
  let copy = original.copying(disabled: true)
  #expect(copy.disabled == true)
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

@Test func copyingOverridesSections() {
  let original = makeResolvedTrack()
  let sections = [
    Section(title: "A", subtitle: nil, style: nil, path: nil, children: [Track(id: "c1", src: "c1")]),
  ]
  let copy = original.copying(sections: sections)
  #expect(copy.sections == sections)
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
    path: "/updated",
    title: "Updated Title",
    artist: "New Artist",
    duration: 240.0,
    favorited: true,
  )
  #expect(copy.path == "/updated")
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
  #expect(copy.path == "/original")
  #expect(copy.children?.count == 1)
  #expect(copy.carPlaySiriListButton == .top)
  #expect(copy.src == "src.mp3")
  #expect(copy.artwork?.url == "art.jpg")
  #expect(copy.artworkSource == ImageSource(uri: "resolved-art.jpg"))
  #expect(copy.subtitle == "Original Subtitle")
  #expect(copy.artist == "Original Artist")
  #expect(copy.album == "Original Album")
  #expect(copy.description == "Original Description")
  #expect(copy.genre == "Rock")
  #expect(copy.duration == 180.0)
  #expect(copy.style == SectionStyle(gridWrap: false, display: .grid, artworkRendering: .stencil))
  #expect(copy.disabled == false)
  #expect(copy.favorited == true)
  #expect(copy.sections?.first?.title == "Group A")
  #expect(copy.live == false)
}
