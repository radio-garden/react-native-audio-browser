import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("PlaybackPersistence", .serialized)
@MainActor
struct PlaybackPersistenceTests {
  private func freshDefaults() -> UserDefaults {
    let suite = "PlaybackPersistenceTests"
    let d = UserDefaults(suiteName: suite)!
    d.removePersistentDomain(forName: suite)
    return d
  }

  private func sampleTrack() -> JsonTrack {
    JsonTrack(
      id: "abc", path: nil, title: "Test FM", subtitle: nil, artwork: nil,
      artist: "City, Country", albumPath: nil, album: nil, description: nil,
      genre: nil, duration: nil, src: "/listen/abc/channel.mp3", request: nil,
      style: JsonStyle(display: "grid", artworkRendering: "stencil"),
      disabled: false, live: true, icyCharset: "windows-1251",
    )
  }

  private func state(positionMs: Double? = nil) -> PersistedPlaybackState {
    PersistedPlaybackState(
      track: sampleTrack(), positionMs: positionMs, repeatMode: "off", shuffleEnabled: false, playbackSpeed: 1.0,
    )
  }

  @Test func roundTrip_preservesState() {
    let persistence = PlaybackPersistence(defaults: freshDefaults()) { state() }
    persistence.save()
    let loaded = persistence.load()
    #expect(loaded?.track.src == "/listen/abc/channel.mp3")
    #expect(loaded?.track.live == true)
    #expect(loaded?.positionMs == nil)
    #expect(loaded?.playbackSpeed == 1.0)
  }

  @Test func load_isNilBeforeAnySave() {
    #expect(PlaybackPersistence(defaults: freshDefaults()) { state() }.load() == nil)
  }

  @Test func nothingToResume_keepsThePreviousSave() {
    let defaults = freshDefaults()
    var current: PersistedPlaybackState? = state(positionMs: 5000)
    let persistence = PlaybackPersistence(defaults: defaults) { current }
    persistence.save()
    current = nil
    persistence.save()
    #expect(persistence.load()?.positionMs == 5000)
  }

  @Test func anActivePlayIntentSavesPeriodically_andOnceWhenItEnds() async throws {
    var positionMs = 0.0
    let persistence = PlaybackPersistence(defaults: freshDefaults(), saveInterval: .milliseconds(10)) {
      positionMs += 1000
      return state(positionMs: positionMs)
    }
    persistence.playIntentDidChange(active: true)
    try await Task.sleep(for: .milliseconds(100))
    #expect((persistence.load()?.positionMs ?? 0) >= 1000)

    persistence.playIntentDidChange(active: false)
    let stopped = try #require(persistence.load()?.positionMs)
    try await Task.sleep(for: .milliseconds(50))
    #expect(persistence.load()?.positionMs == stopped)
  }

  @Test func aSnapshotOfALiveStreamKeepsNoPosition() {
    let snapshot = PlaybackPersistence.snapshot(
      of: Track(src: "s", live: true), state: .playing, position: 12, repeatMode: .off, shuffleEnabled: false,
      rate: 1,
    )
    #expect(snapshot.positionMs == nil)
  }

  @Test func aSnapshotOfAnEndedTrackResumesFromTheStart() {
    let ended = PlaybackPersistence.snapshot(
      of: Track(src: "s"), state: .ended, position: 300, repeatMode: .off, shuffleEnabled: false, rate: 1,
    )
    let paused = PlaybackPersistence.snapshot(
      of: Track(src: "s"), state: .paused, position: 12.5, repeatMode: .track, shuffleEnabled: true, rate: 1.5,
    )
    #expect(ended.positionMs == 0)
    #expect(paused.positionMs == 12500)
    #expect(paused.repeatMode == RepeatMode.track.stringValue)
    #expect(paused.shuffleEnabled)
    #expect(paused.playbackSpeed == 1.5)
  }
}
