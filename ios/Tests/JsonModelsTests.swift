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
    // The shape that shipped broken: a container decodes its children eagerly,
    // so one unparseable row rejected the whole page rather than losing an image.
    let json = """
    { "path": "/favorites", "title": "Favorites", "children": [
      { "title": "Playlist", "artwork": { "light": "https://e.example/l.png", "dark": "https://e.example/d.png" } }
    ] }
    """.data(using: .utf8)!
    let resolved = try JSONDecoder().decode(JsonResolvedTrack.self, from: json).toNitro()
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
    let resolved = try JSONDecoder().decode(JsonResolvedTrack.self, from: json).toNitro()
    let style = resolved.sections?.first?.style
    #expect(style?.display == .grid)
    #expect(style?.gridWrap == false)
  }

  @Test func staleStringStyleDecodesAsNoDeclaration() throws {
    // The retired string vocabulary must never kill the page (tolerant
    // decoding, ADR 0011) — it decodes as an empty block.
    let json = Data(
      """
      {"path":"/home","title":"Home","style":"rail","children":[
        {"title":"C","src":"s","style":"grid"}
      ]}
      """.utf8)
    let resolved = try JSONDecoder().decode(JsonResolvedTrack.self, from: json).toNitro()
    #expect(resolved.style == nil)
    #expect(resolved.children?.first?.style == nil)
  }

  @Test func unknownEnumValuesDecodeAsNoDeclaration() throws {
    let json = Data(
      """
      {"title":"X","style":{"display":"carousel","artworkRendering":"embossed","gridWrap":"yes"}}
      """.utf8)
    let track = try JSONDecoder().decode(JsonTrack.self, from: json).toNitro()
    // All-unknown declarations collapse to "no block" — one shape for "no
    // declaration" on every platform.
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
    // The inheritance-escape sentinel must survive the wire — collapsing it
    // to "no declaration" would let the section value flow back in.
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

  @Test func styleAndDisabledSurviveThePersistenceRoundTrip() throws {
    // Restored queues keep their visible fidelity across process death.
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
    // The persistence snapshot (JsonStyle from a live TrackStyle) must not
    // drop the newer item properties.
    let snapshot = JsonStyle(
      TrackStyle(
        display: nil, artworkRendering: .stencil,
        imageShape: .circular, accessorySymbol: "none",
      ))
    #expect(snapshot?.artworkRendering == "stencil")
    #expect(snapshot?.imageShape == "circular")
    #expect(snapshot?.accessorySymbol == "none")
  }
}
