@testable import AudioBrowserTestable
import Testing

struct TrackIdentityTests {
  @Test func idWinsOverSrc() {
    #expect(Track(id: "stable", src: "https://cdn.example/stream").identity == "stable")
  }

  @Test func blankIdFallsBackToSrc() {
    #expect(Track(id: "", src: "https://cdn.example/stream").identity == "https://cdn.example/stream")
  }

  @Test func nilIdFallsBackToSrc() {
    #expect(Track(src: "https://cdn.example/stream").identity == "https://cdn.example/stream")
  }

  @Test func neitherIdNorSrcHasNoIdentity() {
    #expect(Track(path: "/browse/only").identity == nil)
  }
}
