import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("PlaybackStateStore", .serialized)
struct PlaybackStateStoreTests {
  private func freshDefaults() -> UserDefaults {
    let suite = "PlaybackStateStoreTests"
    let d = UserDefaults(suiteName: suite)!
    d.removePersistentDomain(forName: suite)
    return d
  }

  private func sampleTrack() -> JsonTrack {
    JsonTrack(
      id: "abc", path: nil, title: "Test FM", subtitle: nil, artwork: nil,
      artist: "City, Country", albumPath: nil, album: nil, description: nil,
      genre: nil, duration: nil, src: "/listen/abc/channel.mp3", request: nil,
      style: nil, childrenStyle: nil, live: true,
    )
  }

  @Test func roundTrip_preservesState() {
    let store = PlaybackStateStore(defaults: freshDefaults())
    let state = PersistedPlaybackState(
      track: sampleTrack(), positionMs: nil, repeatMode: "off",
      shuffleEnabled: false, playbackSpeed: 1.0,
    )
    store.save(state)
    let loaded = store.load()
    #expect(loaded?.track.src == "/listen/abc/channel.mp3")
    #expect(loaded?.track.live == true)
    #expect(loaded?.positionMs == nil)
    #expect(loaded?.playbackSpeed == 1.0)
  }

  @Test func load_isNilBeforeAnySave() {
    #expect(PlaybackStateStore(defaults: freshDefaults()).load() == nil)
  }

  @Test func clear_removesState() {
    let store = PlaybackStateStore(defaults: freshDefaults())
    store.save(PersistedPlaybackState(track: sampleTrack(), positionMs: 5000, repeatMode: "off", shuffleEnabled: false, playbackSpeed: 1.0))
    store.clear()
    #expect(store.load() == nil)
  }
}
