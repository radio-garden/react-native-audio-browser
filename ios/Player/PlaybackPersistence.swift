import Foundation

/// Keeps the resumable state in UserDefaults so a cold-start resume can restore the last session
/// without the JS runtime. Swift counterpart of Android's `PlaybackStateStore.kt`.
@MainActor
final class PlaybackPersistence {
  static let standardDefaults = UserDefaults(suiteName: "com.audiobrowser.playback") ?? .standard

  private let defaults: UserDefaults
  private static let key = "playbackState.v1"
  private static let queueKey = "playbackQueue.v1"
  private let saveInterval: Duration
  private let snapshot: @MainActor () -> PersistedPlaybackState?
  private var periodicSave: Task<Void, Never>?

  /// - Parameter snapshot: The state to save; nil when there is nothing to resume.
  init(
    defaults: UserDefaults = standardDefaults,
    saveInterval: Duration = .seconds(5),
    snapshot: @escaping @MainActor () -> PersistedPlaybackState?,
  ) {
    self.defaults = defaults
    self.saveInterval = saveInterval
    self.snapshot = snapshot
  }

  func save() {
    guard let state = snapshot(), let data = try? JSONEncoder().encode(state) else { return }
    defaults.set(data, forKey: Self.key)
  }

  /// Writes the queue the snapshot's index points into; call when its tracks change.
  func saveQueue(_ tracks: [Track]) {
    guard let data = try? JSONEncoder().encode(tracks.map(JsonTrack.init(from:))) else { return }
    defaults.set(data, forKey: Self.queueKey)
  }

  /// The saved session; a queue that won't decode loads as none, keeping the track.
  func load() -> PersistedPlaybackState? {
    guard let data = defaults.data(forKey: Self.key),
          var state = try? JSONDecoder().decode(PersistedPlaybackState.self, from: data)
    else { return nil }
    state.queue = defaults.data(forKey: Self.queueKey).flatMap { try? JSONDecoder().decode([JsonTrack].self, from: $0) }
    return state
  }

  /// Moves on each `clear`, so a restore begun before one can tell it happened.
  private(set) static var clearGeneration = 0

  /// Deletes the saved session and its queue (`clearPersistedSession`).
  static func clear(defaults: UserDefaults = standardDefaults) {
    clearGeneration += 1
    defaults.removeObject(forKey: key)
    defaults.removeObject(forKey: queueKey)
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

  /// The state a resume restores. A live stream keeps no position, and an ended track saves 0:
  /// at .ended the position is the duration, which would end again at once (Android:
  /// savePositionZero).
  static func snapshot(
    of track: Track,
    at index: Int,
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
      queueIndex: index,
    )
  }
}
