import XCTest

@testable import AudioBrowserTestable

@MainActor
final class PlaybackTimerTests: XCTestCase {
  func testNoTicksUntilIntervalSetAndActive() async throws {
    var ticks = 0
    let timer = PlaybackTimer(isActive: { $0 == .playing }) { ticks += 1 }
    timer.onPlaybackStateChanged(.playing) // no interval yet
    try await Task.sleep(nanoseconds: 1_200_000_000)
    XCTAssertEqual(ticks, 0)
  }

  func testTicksWhenIntervalSetAndActive() async throws {
    var ticks = 0
    let timer = PlaybackTimer(isActive: { $0 == .playing }) { ticks += 1 }
    timer.setInterval(1)
    timer.onPlaybackStateChanged(.playing)
    try await Task.sleep(nanoseconds: 2_200_000_000)
    XCTAssertGreaterThanOrEqual(ticks, 1)
  }

  func testStopsWhenStateLeavesActive() async throws {
    var ticks = 0
    let timer = PlaybackTimer(isActive: { $0 == .playing }) { ticks += 1 }
    timer.setInterval(1)
    timer.onPlaybackStateChanged(.playing)
    timer.onPlaybackStateChanged(.paused)
    let after = ticks
    try await Task.sleep(nanoseconds: 1_200_000_000)
    XCTAssertEqual(ticks, after)
  }

  func testNilIntervalStops() async throws {
    var ticks = 0
    let timer = PlaybackTimer(isActive: { $0 == .playing }) { ticks += 1 }
    timer.setInterval(1)
    timer.onPlaybackStateChanged(.playing)
    timer.setInterval(nil) // off-switch
    let after = ticks
    try await Task.sleep(nanoseconds: 1_200_000_000)
    XCTAssertEqual(ticks, after)
  }

  func testIsActivePredicateGatesState() async throws {
    // A "playing-only" predicate must not tick while buffering.
    var ticks = 0
    let timer = PlaybackTimer(isActive: { $0 == .playing }) { ticks += 1 }
    timer.setInterval(1)
    timer.onPlaybackStateChanged(.buffering)
    try await Task.sleep(nanoseconds: 1_200_000_000)
    XCTAssertEqual(ticks, 0)
  }
}
