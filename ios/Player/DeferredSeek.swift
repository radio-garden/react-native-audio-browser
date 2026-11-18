import Foundation

/// A seek target held while the item loads, since AVPlayer ignores `seek(to:)` without a loaded
/// item; the ready transition waits until it lands. The caller issues the seek this hands out and
/// reports when it lands.
struct DeferredSeek {
  enum State: Equatable {
    case idle
    case pending(TimeInterval)
    case inFlight(TimeInterval)
  }

  enum Landing: Equatable {
    case done
    case reseek(TimeInterval)
    case none
  }

  private(set) var state: State = .idle

  /// Bumped by `reset()`, so the completion of a seek against the previous item can tell it is
  /// stale.
  private(set) var generation = 0

  var target: TimeInterval? {
    switch state {
    case let .pending(time), let .inFlight(time): time
    case .idle: nil
    }
  }

  var defersReady: Bool {
    state != .idle
  }

  mutating func capture(_ position: TimeInterval) {
    state = .pending(position)
  }

  /// Marks the pending target in flight and returns it to issue; nil when none is pending.
  mutating func begin() -> TimeInterval? {
    guard case let .pending(time) = state else { return nil }
    state = .inFlight(time)
    return time
  }

  /// A seek cut short keeps the target, for the reload or for the seek that cut it short.
  mutating func land(finished: Bool) -> Landing {
    guard finished else { return .none }
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
