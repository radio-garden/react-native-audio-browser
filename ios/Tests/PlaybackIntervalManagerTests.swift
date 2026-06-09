import Testing
@testable import AudioBrowserTestable

@Suite("PlaybackIntervalManager")
@MainActor
struct PlaybackIntervalManagerTests {

  // MARK: - Not enabled → no ticks even while playing

  @Test("no ticks until enabled and playing")
  func noTicksUntilEnabledAndPlaying() async throws {
    var ticks = 0
    let manager = PlaybackIntervalManager { ticks += 1 }
    manager.onPlaybackStateChanged(.playing)
    try await Task.sleep(for: .milliseconds(1200))
    #expect(ticks == 0)
  }

  // MARK: - Enabled + playing → ticks fire

  @Test("ticks when enabled and playing")
  func ticksWhenEnabledAndPlaying() async throws {
    var ticks = 0
    let manager = PlaybackIntervalManager { ticks += 1 }
    manager.setEnabled(true)
    manager.onPlaybackStateChanged(.playing)
    try await Task.sleep(for: .milliseconds(2200))
    #expect(ticks >= 1)
  }

  // MARK: - Pausing stops ticks

  @Test("stops ticks on pause")
  func stopsTicksOnPause() async throws {
    var ticks = 0
    let manager = PlaybackIntervalManager { ticks += 1 }
    manager.setEnabled(true)
    manager.onPlaybackStateChanged(.playing)
    manager.onPlaybackStateChanged(.paused)
    let after = ticks
    try await Task.sleep(for: .milliseconds(1200))
    #expect(ticks == after)
  }
}
