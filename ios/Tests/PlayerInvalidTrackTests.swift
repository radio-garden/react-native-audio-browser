@testable import AudioBrowserTestable
import Testing

@Suite("Player - invalid tracks")
@MainActor
struct PlayerInvalidTrackTests {
  private let invalid = Track(title: "Invalid")

  private func valid(_ title: String) -> Track {
    Track(src: "https://example.com/\(title).mp3", title: title)
  }

  private func expectInvalidTrack(_ body: () throws -> Void) {
    let error = #expect(throws: TrackPlayerError.QueueError.self, performing: body)
    guard case .invalidTrack = error else {
      Issue.record("Expected invalidTrack, got \(String(describing: error))")
      return
    }
  }

  @Test func loadIntoAnEmptyQueueThrows() {
    let player = makePlayer()

    expectInvalidTrack { try player.load(invalid) }

    #expect(player.tracks.isEmpty)
  }

  @Test func loadOverTheCurrentTrackThrows() throws {
    let player = makePlayer()
    try player.setQueue([valid("a"), valid("b")], initialIndex: 1)

    expectInvalidTrack { try player.load(invalid) }

    #expect(player.tracks.map(\.title) == ["a", "b"])
    #expect(player.currentTrack?.title == "b")
  }

  @Test func addThrowsAndAddsNoneOfTheTracks() throws {
    let player = makePlayer()
    try player.setQueue([valid("a")])

    expectInvalidTrack { try player.add([valid("b"), invalid]) }
    expectInvalidTrack { try player.add([valid("b"), invalid], at: 0) }

    #expect(player.tracks.map(\.title) == ["a"])
  }

  @Test func setQueueThrowsAndKeepsTheQueue() throws {
    let player = makePlayer()
    try player.setQueue([valid("a")])

    expectInvalidTrack { try player.setQueue([valid("b"), invalid]) }

    #expect(player.tracks.map(\.title) == ["a"])
  }

  @Test func aTrackWithOnlyAPathIsQueued() throws {
    let player = makePlayer()

    try player.setQueue([Track(path: "/browse/only", title: "Path")])

    #expect(player.tracks.map(\.title) == ["Path"])
  }
}
