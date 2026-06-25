import Foundation

/// Pure, Cast-SDK-free decisions derived from the receiver's remote state. The
/// gated `CastSessionManager` maps the GCK enums to `CastRemoteState` at the call
/// boundary so the logic below stays testable without the Cast SDK.

extension CastRemoteState.Phase {
  /// Whether the receiver can accept a seek in this phase. Only a loaded receiver
  /// honours one (`playing`/`paused`/`buffering`); while `idle`/`loading` it has no
  /// seekable media and silently drops the seek, so a seek issued then must be
  /// deferred and replayed once the phase becomes seekable.
  var allowsSeek: Bool {
    switch self {
    case .playing, .paused, .buffering: return true
    case .idle, .loading: return false
    }
  }
}

/// What to do when the receiver reports it finished the current item — the
/// repeat-aware end-of-queue decision, mirroring local end-of-track handling.
enum CastEndOfQueueAction {
  /// Not an end-of-queue situation; fall through to normal phase handling.
  case none
  /// Replay the current item on the receiver (repeat-one).
  case repeatCurrent
  /// Advance to the next item, wrapping (repeat-all).
  case advanceNext
  /// Let the queue end naturally (no repeat).
  case endNaturally
}

enum CastRemoteAdvance {
  /// End-of-queue only applies when the receiver went idle *because the content
  /// finished* AND we are on the last item in playback order; otherwise a normal
  /// auto-advance / index-follow handles it.
  static func endOfQueueAction(
    idleReason: CastRemoteState.IdleReason,
    isLastInPlaybackOrder: Bool,
    repeatMode: RepeatMode,
  ) -> CastEndOfQueueAction {
    guard idleReason == .finished, isLastInPlaybackOrder else { return .none }
    switch repeatMode {
    case .track: return .repeatCurrent
    case .queue: return .advanceNext
    case .off: return .endNaturally
    }
  }
}
