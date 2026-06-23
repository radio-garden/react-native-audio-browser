import Foundation

/// Whether an `AVPlayerItemDidPlayToEndTime` notification represents a genuine
/// end of the track, or a stall masquerading as one.
enum EndOfTrackOutcome: Equatable {
  /// Genuine end — advance the queue / complete.
  case ended
  /// Not a real end (a dropped live stream, or a mid-stream buffer exhaustion
  /// reported as EOF) — recover instead of advancing.
  case stalled
}

/// Pure decision for how to treat an end-of-item notification, kept free of
/// AVFoundation so it can be unit-tested off-device. iOS surfaces a dropped
/// connection on a live stream — and sometimes a mid-stream buffer underrun —
/// as a "played to end", which would otherwise stop the station or skip the
/// track.
struct EndOfTrackJudgement {
  let isLive: Bool
  let currentTime: Double
  let duration: Double

  var outcome: EndOfTrackOutcome {
    // Live streams are infinite; an end-of-item here is a dropped connection.
    if isLive { return .stalled }
    // Indefinite/unknown duration — can't validate the position, trust iOS.
    guard duration.isFinite, duration > 0 else { return .ended }
    // More than 5% short of the end → a stall reported as EOF, not a real finish.
    if currentTime + duration * 0.05 < duration { return .stalled }
    return .ended
  }
}
