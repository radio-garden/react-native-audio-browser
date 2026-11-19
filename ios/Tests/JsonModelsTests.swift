@testable import AudioBrowserTestable
import Foundation
import Testing

@Suite("JsonTrack.request")
struct JsonTrackRequestTests {
  @Test func decodesRequestOntoTrack() throws {
    let json = """
    { "title": "X", "src": "/s", "request": { "userAgent": "UA-X",
      "headers": { "Referer": "https://e.example" }, "query": { "t": "1" } } }
    """.data(using: .utf8)!
    let jsonTrack = try JSONDecoder().decode(JsonTrack.self, from: json)
    let track = jsonTrack.toNitro()
    #expect(track.request?.userAgent == "UA-X")
    #expect(track.request?.headers?["Referer"] == "https://e.example")
    #expect(track.request?.query?["t"] == "1")
  }
}

@Suite("JsonTrack.artwork")
struct JsonTrackArtworkTests {
  @Test func decodesASingleUrl() throws {
    let json = #"{ "title": "X", "artwork": "https://e.example/a.png" }"#.data(using: .utf8)!
    let track = try JSONDecoder().decode(JsonTrack.self, from: json).toNitro()
    #expect(track.artwork == .first("https://e.example/a.png"))
  }

  @Test func decodesAPerAppearancePair() throws {
    let json = """
    { "title": "X", "artwork": { "light": "https://e.example/l.png", "dark": "https://e.example/d.png" } }
    """.data(using: .utf8)!
    let track = try JSONDecoder().decode(JsonTrack.self, from: json).toNitro()
    #expect(track.artwork?.variants?.light == "https://e.example/l.png")
    #expect(track.artwork?.variants?.dark == "https://e.example/d.png")
    // Callers that cannot express appearance get the dark one.
    #expect(track.artwork?.url == "https://e.example/d.png")
  }

  @Test func decodesAPairNestedInChildren() throws {
    // A container decodes its children eagerly, so a child the decoder rejects fails the whole page.
    let json = """
    { "path": "/favorites", "title": "Favorites", "children": [
      { "title": "Playlist", "artwork": { "light": "https://e.example/l.png", "dark": "https://e.example/d.png" } }
    ] }
    """.data(using: .utf8)!
    let resolved = try JSONDecoder().decode(JsonResolvedTrack.self, from: json).toNitro(requestedPath: "/requested")
    #expect(resolved.children?.first?.artwork?.variants?.dark == "https://e.example/d.png")
  }

  @Test func roundTripsBothShapes() throws {
    for artwork: JsonArtwork in [.single("a.png"), .variants(light: "l.png", dark: "d.png")] {
      let encoded = try JSONEncoder().encode(JsonTrack(title: "X", artwork: artwork))
      let decoded = try JSONDecoder().decode(JsonTrack.self, from: encoded)
      #expect(decoded.artwork == artwork)
    }
  }
}

@Suite("JsonTrack.favorited")
struct JsonTrackFavoritedTests {
  private func track(_ json: String) throws -> Track {
    try JSONDecoder().decode(JsonTrack.self, from: Data(json.utf8)).toNitro()
  }

  @Test func decodesTheValueTheResponseCarries() throws {
    #expect(try track(#"{ "title": "X", "src": "a", "favorited": true }"#).favorited == true)
    #expect(try track(#"{ "title": "X", "src": "a", "favorited": false }"#).favorited == false)
    #expect(try track(#"{ "title": "X", "src": "a" }"#).favorited == nil)
  }

  @Test func theValueOfTheResponseWinsOverHydration() throws {
    var favorites = FavoriteSet()
    favorites.enabled = true
    _ = favorites.replace(with: ["b"])
    let hearted = try track(#"{ "title": "A", "src": "a", "favorited": true }"#)
    let unhearted = try track(#"{ "title": "B", "src": "b", "favorited": false }"#)
    #expect(favorites.hydrate(hearted).favorited == true)
    #expect(favorites.hydrate(unhearted).favorited == false)
  }

  @Test func decodesOnAPageAndOnItsTracks() throws {
    let json = Data(
      """
      {"path":"/track/1","title":"Page","src":"p","favorited":true,"sections":[
        {"children":[{"title":"A","src":"a","favorited":true},{"title":"B","src":"b"}]}
      ]}
      """.utf8)
    let page = try JSONDecoder().decode(JsonResolvedTrack.self, from: json).toNitro(requestedPath: "/track/1")
    #expect(page.favorited == true)
    #expect(page.sections?.first?.children.map(\.favorited) == [true, nil])
  }
}

// MARK: - Style blocks (ADR 0011)

@Suite("JsonTrack.style")
struct JsonStyleTests {
  @Test func decodesABlockOntoTrackStyle() throws {
    let json = Data(
      """
      {"title":"X","style":{"display":"grid","artworkRendering":"stencil"}}
      """.utf8)
    let track = try JSONDecoder().decode(JsonTrack.self, from: json).toNitro()
    #expect(track.style?.display == .grid)
    #expect(track.style?.artworkRendering == .stencil)
  }

  @Test func decodesABlockOntoSectionStyle() throws {
    let json = Data(
      """
      {"path":"/home","title":"Home","sections":[
        {"style":{"display":"grid","gridWrap":false},"children":[{"title":"C","src":"s"}]}
      ]}
      """.utf8)
    let resolved = try JSONDecoder().decode(JsonResolvedTrack.self, from: json).toNitro(requestedPath: "/requested")
    let style = resolved.sections?.first?.style
    #expect(style?.display == .grid)
    #expect(style?.gridWrap == false)
  }

  @Test func staleStringStyleDecodesAsNoDeclaration() throws {
    // The retired string vocabulary (ADR 0011) decodes as no block rather than failing the page.
    let json = Data(
      """
      {"path":"/home","title":"Home","style":"rail","children":[
        {"title":"C","src":"s","style":"grid"}
      ]}
      """.utf8)
    let resolved = try JSONDecoder().decode(JsonResolvedTrack.self, from: json).toNitro(requestedPath: "/requested")
    #expect(resolved.style == nil)
    #expect(resolved.children?.first?.style == nil)
  }

  @Test func unknownEnumValuesDecodeAsNoDeclaration() throws {
    let json = Data(
      """
      {"title":"X","style":{"display":"carousel","artworkRendering":"embossed","gridWrap":"yes"}}
      """.utf8)
    let track = try JSONDecoder().decode(JsonTrack.self, from: json).toNitro()
    // All-unknown declarations collapse to no block, the one shape for "no declaration" on every
    // platform.
    #expect(track.style == nil)
  }

  @Test func decodesDisabled() throws {
    let json = Data("""
    {"title":"X","src":"s","disabled":true}
    """.utf8)
    let track = try JSONDecoder().decode(JsonTrack.self, from: json).toNitro()
    #expect(track.disabled == true)
  }

  @Test func decodesImageShapeAndAccessorySymbol() throws {
    let json = Data(
      """
      {"title":"X","style":{"imageShape":"circular","accessorySymbol":"lock.fill"}}
      """.utf8)
    let track = try JSONDecoder().decode(JsonTrack.self, from: json).toNitro()
    #expect(track.style?.imageShape == .circular)
    #expect(track.style?.accessorySymbol == "lock.fill")
  }

  @Test func accessorySymbolNoneIsAValueNotEmptiness() throws {
    // "none" escapes inheritance; read as no declaration, it would let the section's symbol back in.
    let json = Data(
      """
      {"title":"X","style":{"accessorySymbol":"none"}}
      """.utf8)
    let track = try JSONDecoder().decode(JsonTrack.self, from: json).toNitro()
    #expect(track.style?.accessorySymbol == "none")
  }

  @Test func unknownImageShapeDecodesAsNoDeclaration() throws {
    let json = Data(
      """
      {"title":"X","style":{"imageShape":"hexagonal"}}
      """.utf8)
    let track = try JSONDecoder().decode(JsonTrack.self, from: json).toNitro()
    #expect(track.style == nil)
  }

  @Test func decodesTheCardFamily() throws {
    let json = Data(
      """
      {"path":"/home","title":"Home","sections":[
        {"style":{"display":"grid","gridTile":"card","cardTint":"#1e3a8a","cardImage":"background"},
         "children":[{"title":"C","src":"s"}]}
      ]}
      """.utf8)
    let resolved = try JSONDecoder().decode(JsonResolvedTrack.self, from: json).toNitro(requestedPath: "/requested")
    let style = resolved.sections?.first?.style
    #expect(style?.gridTile == .card)
    #expect(style?.cardTint == "#1e3a8a")
    #expect(style?.cardImage == .background)
  }

  @Test func decodesTheImageFamily() throws {
    let json = Data(
      """
      {"path":"/home","title":"Home","sections":[
        {"style":{"display":"grid","gridTile":"image","imageShape":"circular"},
         "children":[{"title":"C","src":"s"}]}
      ]}
      """.utf8)
    let resolved = try JSONDecoder().decode(JsonResolvedTrack.self, from: json).toNitro(requestedPath: "/requested")
    let style = resolved.sections?.first?.style
    #expect(style?.gridTile == .image)
    #expect(style?.imageShape == .circular)
  }

  @Test func unknownGridTileDecodesAsNoDeclaration() throws {
    let json = Data(
      """
      {"path":"/home","title":"Home","sections":[
        {"style":{"gridTile":"hexagon"},"children":[{"title":"C","src":"s"}]}
      ]}
      """.utf8)
    let resolved = try JSONDecoder().decode(JsonResolvedTrack.self, from: json).toNitro(requestedPath: "/requested")
    #expect(resolved.sections?.first?.style == nil)
  }

  @Test func styleAndDisabledSurviveThePersistenceRoundTrip() throws {
    let original = JsonTrack(
      title: "X", src: "s",
      style: JsonStyle(
        display: "grid", artworkRendering: "stencil",
        imageShape: "circular", accessorySymbol: "lock.fill",
      ),
      disabled: true,
    )
    let encoded = try JSONEncoder().encode(original)
    let decoded = try JSONDecoder().decode(JsonTrack.self, from: encoded)
    #expect(decoded.style == original.style)
    #expect(decoded.disabled == true)
  }

  @Test func liveBlockSnapshotsAllItemProperties() throws {
    let snapshot = JsonStyle(
      TrackStyle(
        display: nil, artworkRendering: .stencil,
        imageShape: .circular, accessorySymbol: "none",
        cardTint: "#1e3a8a", cardImage: .background,
      ))
    #expect(snapshot?.artworkRendering == "stencil")
    #expect(snapshot?.imageShape == "circular")
    #expect(snapshot?.accessorySymbol == "none")
    #expect(snapshot?.cardTint == "#1e3a8a")
    #expect(snapshot?.cardImage == "background")
  }
}

@Suite("The page an endpoint answers")
struct JsonPageShapeTests {
  @Test func aBrowsePageWithoutAPathTakesTheRequestedPath() throws {
    let json = Data(#"{"title":"Jazz","children":[{"title":"C","src":"s"}]}"#.utf8)
    let page = try JSONDecoder().decode(JsonBrowsePage.self, from: json).page.toNitro(requestedPath: "/genres/jazz")
    #expect(page.path == "/genres/jazz")
    #expect(page.title == "Jazz")
    #expect(page.children?.map(\.title) == ["C"])
  }

  @Test func aPagesOwnPathWins() throws {
    let json = Data(#"{"path":"/jazz","title":"Jazz"}"#.utf8)
    let page = try JSONDecoder().decode(JsonBrowsePage.self, from: json).page.toNitro(requestedPath: "/genres/jazz")
    #expect(page.path == "/jazz")
  }

  @Test func aTabsPageNeedsNeitherTitleNorPath() throws {
    let json = Data(#"{"children":[{"title":"Home","path":"/home"}]}"#.utf8)
    let page = try JSONDecoder().decode(JsonResolvedTrack.self, from: json)
      .toNitro(requestedPath: SimpleRouter.tabsRoutePath)
    #expect(page.title == "")
    #expect(page.path == SimpleRouter.tabsRoutePath)
    #expect(page.children?.map(\.path) == ["/home"])
  }

  @Test func aBrowsePageWithoutATitleDoesNotDecode() {
    let json = Data(#"{"children":[]}"#.utf8)
    #expect(throws: DecodingError.self) {
      _ = try JSONDecoder().decode(JsonBrowsePage.self, from: json)
    }
  }
}

@Suite("JsonTrack persistence")
struct JsonTrackPersistenceTests {
  @Test func aTrackSurvivesTheRoundTripThroughItsSnapshot() throws {
    let track = Track(
      id: "t1", path: "/station/1", src: "https://example.com/a.mp3",
      artwork: .first("https://example.com/a.jpg"),
      request: TrackRequest(userAgent: "Agent", headers: ["X": "1"], query: ["q": "2"]),
      title: "Title", subtitle: "Subtitle", artist: "Artist", albumPath: "/album/1", album: "Album",
      description: "Description", genre: "Genre", duration: 123,
      disabled: false, live: true, icyCharset: "windows-1251",
    )
    let data = try JSONEncoder().encode(JsonTrack(from: track))
    let restored = try JSONDecoder().decode(JsonTrack.self, from: data).toNitro()
    #expect(restored == track)
  }

  /// A favorite is the app's state, never persisted with the track; neither is the resolved artwork.
  @Test func favoritedAndTheResolvedArtworkAreNotPersisted() throws {
    let track = Track(
      id: "t1", src: "https://example.com/a.mp3",
      artworkSource: ImageSource(uri: "https://example.com/r.jpg", method: nil, headers: nil, body: nil),
      title: "Title", favorited: true,
    )
    let restored = JsonTrack(from: track).toNitro()
    #expect(restored.favorited == nil)
    #expect(restored.artworkSource == nil)
  }
}
