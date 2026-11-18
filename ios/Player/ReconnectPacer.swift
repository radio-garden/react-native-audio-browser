import Foundation

enum ReconnectWait: Equatable {
  case after(ms: Int)
  case untilOnline
  case giveUp
}

/// When the next connect goes out after one ends (ADR 0014, rules 5 to 8), for one sequence of
/// connections made one at a time. Time comes in as milliseconds on one monotonic clock; Android's
/// `ReconnectPacer` runs the same `spec/retry-cases.json`.
///
/// The 1 s floor counts from when the last connection started and the backoff from when it ended.
struct ReconnectPacer {
  static let minConnectIntervalMs = 1_000
  static let firstBackoffMs = 1_000
  static let maxBackoffMs = 5_000
  static let backoffGrowth = 1.5

  /// Connections in a row that may deliver nothing before giving up; nil for no limit.
  var maxRetries: Int?

  private var online: Bool
  private var playIntent = false
  private var deliveredNothingInARow = 0
  private var lastStartedAtMs: Int?
  private var nextConnectMs: Int?

  init(maxRetries: Int? = nil, online: Bool) {
    self.maxRetries = maxRetries
    self.online = online
  }

  /// For a new Load, never a retry's reload: starts the count and the waits over.
  mutating func reset() {
    deliveredNothingInARow = 0
    lastStartedAtMs = nil
    nextConnectMs = nil
  }

  mutating func playIntentChanged(_ up: Bool) {
    if up, !playIntent { reset() }
    playIntent = up
  }

  mutating func onlineChanged(_ online: Bool) {
    if online, !self.online { nextConnectMs = lastStartedAtMs.map { $0 + Self.minConnectIntervalMs } }
    self.online = online
  }

  /// A connection that ends offline does not grow the backoff: it delivered nothing for want of a
  /// network, not of a source.
  mutating func connectionEnded(deliveredAudio: Bool, startedAtMs: Int, atMs now: Int) {
    lastStartedAtMs = startedAtMs
    nextConnectMs = startedAtMs + Self.minConnectIntervalMs
    if deliveredAudio {
      deliveredNothingInARow = 0
    } else if online {
      deliveredNothingInARow += 1
      nextConnectMs = max(nextConnectMs ?? now, now + backoffMs)
    }
  }

  func wait(atMs now: Int) -> ReconnectWait {
    if let maxRetries, deliveredNothingInARow > maxRetries { return .giveUp }
    guard online else { return .untilOnline }
    return .after(ms: max(0, (nextConnectMs ?? now) - now))
  }

  private var backoffMs: Int {
    let grown = Double(Self.firstBackoffMs) * pow(Self.backoffGrowth, Double(deliveredNothingInARow - 1))
    return Int(min(grown, Double(Self.maxBackoffMs)))
  }
}
