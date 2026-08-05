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
    { "url": "/favorites", "title": "Favorites", "children": [
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
