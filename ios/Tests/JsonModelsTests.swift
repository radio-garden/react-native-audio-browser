import Foundation
import Testing
@testable import AudioBrowserTestable

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
