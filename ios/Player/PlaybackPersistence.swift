import Foundation

/// Keeps the resumable state in UserDefaults, so a cold start can resume without the JS runtime.
/// Counterpart of Android's `PlaybackStateStore`.
@MainActor
final class PlaybackPersistence {
  static let standardDefaults = UserDefaults(suiteName: "com.audiobrowser.playback") ?? .standard

  private let defaults: UserDefaults
  private let key = "playbackState.v1"
  private let saveInterval: Duration
  private let snapshot: @MainActor () -> PersistedPlaybackState?
  private var periodicSave: Task<Void, Never>?

  init(
    defaults: UserDefaults = standardDefaults,
    saveInterval: Duration = .seconds(5),
    snapshot: @escaping @MainActor () -> PersistedPlaybackState?,
  ) {
    self.defaults = defaults
    self.saveInterval = saveInterval
    self.snapshot = snapshot
  }

  /// With no snapshot, nothing is to be resumed and the last saved state stays.
  func save() {
    guard let state = snapshot(), let data = try? JSONEncoder().encode(state) else { return }
    defaults.set(data, forKey: key)
  }

  func load() -> PersistedPlaybackState? {
    guard let data = defaults.data(forKey: key) else { return nil }
    return try? JSONDecoder().decode(PersistedPlaybackState.self, from: data)
  }

  /// Saves every `saveInterval` while `active`, and once when it turns false.
  func playIntentDidChange(active: Bool) {
    periodicSave?.cancel()
    periodicSave = nil
    guard active else {
      save()
      return
    }
    periodicSave = Task { [weak self, saveInterval] in
      while !Task.isCancelled {
        try? await Task.sleep(for: saveInterval)
        guard !Task.isCancelled else { return }
        self?.save()
      }
    }
  }

  static func snapshot(
    of track: Track,
    state: PlaybackState,
    position: TimeInterval,
    repeatMode: RepeatMode,
    shuffleEnabled: Bool,
    rate: Float,
  ) -> PersistedPlaybackState {
    PersistedPlaybackState(
      track: JsonTrack(from: track),
      // At .ended the position is the duration, which would end again at once on resume.
      positionMs: track.live == true ? nil : (state == .ended ? 0 : position * 1000),
      repeatMode: repeatMode.stringValue,
      shuffleEnabled: shuffleEnabled,
      playbackSpeed: rate,
    )
  }
}
