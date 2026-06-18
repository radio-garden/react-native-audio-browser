import Foundation

/// Persists the player's resumable state to UserDefaults so a cold-start
/// "resume" intent can restore the last session without the JS runtime.
/// Swift counterpart of Android's `PlaybackStateStore.kt`.
final class PlaybackStateStore {
  private let defaults: UserDefaults
  private let key = "playbackState.v1"

  init(defaults: UserDefaults = UserDefaults(suiteName: "com.audiobrowser.playback") ?? .standard) {
    self.defaults = defaults
  }

  func save(_ state: PersistedPlaybackState) {
    guard let data = try? JSONEncoder().encode(state) else { return }
    defaults.set(data, forKey: key)
  }

  func load() -> PersistedPlaybackState? {
    guard let data = defaults.data(forKey: key) else { return nil }
    return try? JSONDecoder().decode(PersistedPlaybackState.self, from: data)
  }

  func clear() {
    defaults.removeObject(forKey: key)
  }
}
