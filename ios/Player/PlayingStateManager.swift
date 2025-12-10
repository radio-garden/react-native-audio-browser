import Foundation
import NitroModules

/// Manages the playing state (playing and buffering flags) and notifies when they change.
class PlayingStateManager {
  private(set) var playing: Bool = false
  private(set) var buffering: Bool = false

  private let onChange: (PlayingState) -> Void

  init(onChange: @escaping (PlayingState) -> Void) {
    self.onChange = onChange
  }

  func update(playWhenReady: Bool, state: PlaybackState) {
    let newPlaying = playWhenReady && !(state == .error || state == .ended || state == .none)
    let newBuffering = playWhenReady && (state == .loading || state == .buffering)

    if newPlaying != playing || newBuffering != buffering {
      playing = newPlaying
      buffering = newBuffering
      onChange(PlayingState(playing: playing, buffering: buffering))
    }
  }

  func toPlayingState() -> PlayingState {
    PlayingState(playing: playing, buffering: buffering)
  }
}
