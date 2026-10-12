import Foundation

/// A seek that arrives while an item loads. AVPlayer ignores `seek(to:)` without a loaded item,
/// so the target is held until the item is ready, and the ready transition waits until it lands.
/// Pure state: the caller issues the seek this hands out and reports when it lands.
struct DeferredSeek {
  enum State: Equatable {
    case idle
    case pending(TimeInterval)
    case inFlight(TimeInterval)
  }

  /// What to do once an issued seek lands.
  enum Landing: Equatable {
    /// The target is reached: the load may become ready.
    case done
    /// A newer target arrived meanwhile: issue it.
    case reseek(TimeInterval)
    /// No deferred seek was in flight.
    case none
  }

  private(set) var state: State = .idle

  /// Identifies the item a seek was issued against. `reset()` bumps it, so the completion of a
  /// seek against the previous item can tell it is stale.
  private(set) var generation = 0

  /// The target, pending or in flight; `seekBy` offsets from it.
  var target: TimeInterval? {
    switch state {
    case let .pending(time), let .inFlight(time): time
    case .idle: nil
    }
  }

  var defersReady: Bool {
    state != .idle
  }

  /// Holds `position` as the target, replacing a pending one or one in flight.
  mutating func capture(_ position: TimeInterval) {
    state = .pending(position)
  }

  /// Hands out the pending target to issue, marking it in flight; nil when none is pending.
  mutating func begin() -> TimeInterval? {
    guard case let .pending(time) = state else { return nil }
    state = .inFlight(time)
    return time
  }

  mutating func land() -> Landing {
    switch state {
    case .inFlight:
      state = .idle
      return .done
    case .pending:
      guard let time = begin() else { return .none }
      return .reseek(time)
    case .idle:
      return .none
    }
  }

  mutating func reset() {
    state = .idle
    generation += 1
  }
}
