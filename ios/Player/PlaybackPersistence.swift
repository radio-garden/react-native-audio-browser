import Foundation

/// Keeps the player's resumable state in UserDefaults so a cold-start resume can restore the
/// last session without the JS runtime. Saves on demand and, while the play intent is active,
/// every `saveInterval`. Swift counterpart of Android's `PlaybackStateStore.kt`.
@MainActor
final class PlaybackPersistence {
  private let defaults: UserDefaults
  private let key = "playbackState.v1"
  private let saveInterval: Duration
  private let snapshot: @MainActor () -> PersistedPlaybackState?
  private var periodicSave: Task<Void, Never>?

  /// - Parameter snapshot: The state to save; nil when there is nothing to resume.
  init(
    defaults: UserDefaults = UserDefaults(suiteName: "com.audiobrowser.playback") ?? .standard,
    saveInterval: Duration = .seconds(5),
    snapshot: @escaping @MainActor () -> PersistedPlaybackState?,
  ) {
    self.defaults = defaults
    self.saveInterval = saveInterval
    self.snapshot = snapshot
  }

  func save() {
    guard let state = snapshot(), let data = try? JSONEncoder().encode(state) else { return }
    defaults.set(data, forKey: key)
  }

  func load() -> PersistedPlaybackState? {
    guard let data = defaults.data(forKey: key) else { return nil }
    return try? JSONDecoder().decode(PersistedPlaybackState.self, from: data)
  }

  /// Saves periodically while `active`, and once when it turns false.
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

  /// The state a resume restores. A live stream keeps no position, and an ended track resumes
  /// from 0: at .ended the position is the duration, which would re-end at once (Android twin:
  /// savePositionZero).
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
      positionMs: track.live == true ? nil : (state == .ended ? 0 : position * 1000),
      repeatMode: repeatMode.stringValue,
      shuffleEnabled: shuffleEnabled,
      playbackSpeed: rate,
    )
  }
}
