@testable import AudioBrowserTestable
import Foundation
import Testing
import XCTest

/// The first of three on-demand tracks plays at 120 s of 300 s, 180 s buffered. The mock engine,
/// like AVPlayer, goes on reporting that item until the next one is prepared.
@MainActor
private func makePlayingFirstOfThree() throws -> (Player, MockPlayerEngine, MockPlayerCallbacks) {
  let engine = MockPlayerEngine()
  let player = makePlayer(engine: engine)
  let callbacks = MockPlayerCallbacks()
  player.callbacks = callbacks
  try player.setQueue(
    ["a", "b", "c"].map { Track(id: $0, src: "https://example.com/\($0).mp3", title: $0) },
    playWhenReady: true,
  )
  playPreparedItem(player, engine)
  engine.currentTime = 120
  engine.resumePosition = 120
  engine.duration = 300
  engine.bufferedPosition = 180
  return (player, engine, callbacks)
}

@MainActor
private func playPreparedItem(_ player: Player, _ engine: MockPlayerEngine) {
  engine.hasItem = true
  engine.hasLoadedAsset = true
  engine.emit(.itemPrepared(likelyToKeepUp: true))
  player.transition(.avPlayerPlaying)
}

@Suite("Player - progress")
@MainActor
struct PlayerProgressTests {
  @Test func thePositionWhileATrackLoadsIsWhereItWillStart() throws {
    let (player, _, _) = try makePlayingFirstOfThree()
    #expect(player.currentTime == 120)

    player.next()
    #expect(player.state == .loading)
    #expect(player.currentTime == 0)

    player.seek(to: 30)
    #expect(player.currentTime == 30)
  }

  @Test func aTrackSkippedWhileItLoadedWasLeftAtItsOwnPosition() throws {
    let (player, _, callbacks) = try makePlayingFirstOfThree()

    player.next()
    #expect(callbacks.activeTrackChanges.last?.lastPosition == 120)

    player.next()
    #expect(callbacks.activeTrackChanges.last?.lastTrack?.id == "b")
    #expect(callbacks.activeTrackChanges.last?.lastPosition == 0)
  }
}

/// XCTest, whose runner turns the main run loop the progress timer fires on.
@MainActor
final class PlayerProgressEventTests: XCTestCase {
  /// The next progress event the timer emits, at a 10 ms interval.
  private func nextProgress(
    _ player: Player, _ callbacks: MockPlayerCallbacks,
  ) async throws -> PlaybackProgressUpdatedEvent {
    callbacks.progressUpdates.removeAll()
    player.setProgressUpdateInterval(0.01)
    defer { player.setProgressUpdateInterval(nil) }
    for _ in 0 ..< 500 where callbacks.progressUpdates.isEmpty {
      try await Task.sleep(for: .milliseconds(10))
    }
    return try XCTUnwrap(callbacks.progressUpdates.first)
  }

  func testProgressWhileTheNextTrackLoadsIsNotThePreviousTracks() async throws {
    let (player, engine, callbacks) = try makePlayingFirstOfThree()

    player.next()
    XCTAssertEqual(player.state, .loading)
    let loading = try await nextProgress(player, callbacks)
    XCTAssertEqual(loading.track, 1)
    XCTAssertEqual(loading.position, 0)
    XCTAssertEqual(loading.duration, 0)
    XCTAssertEqual(loading.buffered, 0)

    engine.currentTime = 0
    engine.duration = 200
    engine.bufferedPosition = 10
    playPreparedItem(player, engine)
    let playing = try await nextProgress(player, callbacks)
    XCTAssertEqual(playing.track, 1)
    XCTAssertEqual(playing.duration, 200)
    XCTAssertEqual(playing.buffered, 10)
  }
}
