/// Whether the player holds the audio session. A latch: a track loaded after a release, but not
/// played, does not take the session back from the other app's audio.
struct AudioSessionHold: Equatable {
  private(set) var engaged: Bool

  init(engaged: Bool = false) {
    self.engaged = engaged
  }

  mutating func step(hasTrack: Bool, terminal: Bool, playWhenReady: Bool) {
    if terminal || !hasTrack {
      engaged = false
    } else if playWhenReady {
      engaged = true
    }
  }

  /// An interruption that may resume holds the session outside the latch, so dropping its resume
  /// frees it at once.
  func holds(resumingAfterInterruption: Bool) -> Bool {
    engaged || resumingAfterInterruption
  }
}
