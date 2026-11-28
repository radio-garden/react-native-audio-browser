import Foundation

/// Mirrors Android's `PlaybackStateStore.PersistedState`. `positionMs` is nil for live streams.
struct PersistedPlaybackState: Codable {
  let track: JsonTrack
  let positionMs: Double?
  let repeatMode: String
  let shuffleEnabled: Bool
  let playbackSpeed: Float
  /// The queue `track` was current in, at `queueIndex`. Kept under its own key, written only when
  /// the queue changes (`PlaybackPersistence.saveQueue`), so it is not part of this encoding.
  var queue: [JsonTrack]? = nil
  let queueIndex: Int?

  private enum CodingKeys: String, CodingKey {
    case track, positionMs, repeatMode, shuffleEnabled, playbackSpeed, queueIndex
  }

  /// The stored queue, when `track` is still the one at its index.
  var storedQueue: (tracks: [Track], selectedIndex: Int)? {
    guard let queue, let queueIndex, queue.indices.contains(queueIndex) else { return nil }
    let tracks = queue.map { $0.toNitro() }
    guard tracks[queueIndex].identity == track.toNitro().identity else { return nil }
    return (tracks, queueIndex)
  }
}
