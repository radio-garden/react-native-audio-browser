import Foundation

enum EndOfTrackOutcome: Equatable {
  case ended
  /// A stall reported as an end: recover rather than advance.
  case stalled
}

/// Judges an `AVPlayerItemDidPlayToEndTime`: iOS reports a dropped live connection, and
/// sometimes a mid-stream buffer underrun, as a played-to-end, which would stop the station or
/// skip the track. Free of AVFoundation so it tests off-device.
struct EndOfTrackJudgement {
  let isLive: Bool
  let currentTime: Double
  let duration: Double

  var outcome: EndOfTrackOutcome {
    if isLive { return .stalled }
    // No known duration to check the position against: trust iOS.
    guard duration.isFinite, duration > 0 else { return .ended }
    // More than 5% short of the end is a stall, not a finish.
    if currentTime + duration * 0.05 < duration { return .stalled }
    return .ended
  }
}
