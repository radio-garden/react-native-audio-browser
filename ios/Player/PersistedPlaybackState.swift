import Foundation

/// A serialisable snapshot of the player's resumable state. Mirrors Android's
/// `PlaybackStateStore.PersistedState`. `positionMs` is `nil` for live streams.
struct PersistedPlaybackState: Codable {
  let track: JsonTrack
  let positionMs: Double?
  let repeatMode: String
  let shuffleEnabled: Bool
  let playbackSpeed: Float
}
