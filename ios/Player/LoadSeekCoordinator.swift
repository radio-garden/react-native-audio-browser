import AVFoundation
import os.log

/// AVPlayer's `seek(to:)` is a no-op when there's no loaded `currentItem`,
/// so we must manually defer seeks that arrive during loading and execute them
/// once the AVPlayerItem is ready. This coordinator encapsulates the three
/// sub-problems (capture, execute, coordinate) that were previously spread
/// across `pendingSeek`, `loadSeekInFlight`, and guards in multiple callbacks.
@MainActor
final class LoadSeekCoordinator {
  enum State {
    case idle
    case pendingSeek(time: TimeInterval)
    case seekInFlight(time: TimeInterval)
  }

  private let logger = Logger(subsystem: "com.audiobrowser", category: "LoadSeekCoordinator")
  private(set) var state: State = .idle

  /// Used by `seekBy()` to offset from the deferred position.
  var pendingTime: TimeInterval? {
    switch state {
    case let .pendingSeek(time): time
    case let .seekInFlight(time): time
    case .idle: nil
    }
  }

  var shouldDeferReadyTransition: Bool {
    switch state {
    case .idle: false
    case .pendingSeek, .seekInFlight: true
    }
  }

  func capture(position: Double) {
    if case .seekInFlight = state {
      logger.debug("overriding in-flight seek with new target \(position)s")
    }
    state = .pendingSeek(time: position)
    logger.debug("captured pending seek to \(position)s")
  }

  /// Returns `true` if a seek was initiated -- caller should NOT transition to `.ready` yet.
  func executeIfPending(on player: AVPlayer, delegate: (any SeekCompletionHandler)?) -> Bool {
    guard case let .pendingSeek(time) = state else { return false }

    logger.debug("executing deferred seek to \(time)s")
    state = .seekInFlight(time: time)

    let cmTime = CMTime(seconds: time, preferredTimescale: 1000)
    player
      .seek(to: cmTime, toleranceBefore: CMTime.zero, toleranceAfter: CMTime.zero) { finished in
        Task { @MainActor in
          delegate?.handleSeekCompleted(to: time, didFinish: finished)
        }
      }

    return true
  }

  /// Returns `true` if done (caller should transition `.loading` -> `.ready`).
  /// Returns `false` if a new seek was queued while in-flight (re-executes automatically).
  func seekDidComplete(on player: AVPlayer, delegate: (any SeekCompletionHandler)?) -> Bool {
    switch state {
    case .seekInFlight:
      logger.debug("seek landed, clearing coordinator")
      state = .idle
      return true
    case .pendingSeek:
      // A new seek arrived while the previous one was in-flight.
      // Execute it now -- don't transition to .ready yet.
      logger.debug("seek landed, but a new seek is pending -- re-executing")
      _ = executeIfPending(on: player, delegate: delegate)
      return false
    case .idle:
      return false
    }
  }

  func reset() {
    state = .idle
  }
}
