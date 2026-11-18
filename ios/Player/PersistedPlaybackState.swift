import Foundation

/// Mirrors Android's `PlaybackStateStore.PersistedState`. A saved state that no longer decodes is
/// dropped, so a breaking change bumps `PlaybackPersistence`'s key.
struct PersistedPlaybackState: Codable {
  let track: JsonTrack
  let positionMs: Double?
  let repeatMode: String
  let shuffleEnabled: Bool
  let playbackSpeed: Float
}
