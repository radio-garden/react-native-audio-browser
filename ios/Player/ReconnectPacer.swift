import Foundation

enum ReconnectWait: Equatable {
  case after(ms: Int)
  /// Offline: no connect until the network returns; ask again then.
  case untilOnline
  /// More than `maxRetries` connections in a row delivered nothing.
  case giveUp
}

/// When the next connect goes out after one ends (docs/adr/0014, rules 5 to 8), for one sequence of
/// connections made one at a time. Pure: time comes in as milliseconds on one monotonic clock.
///
/// The 1 s floor counts from when the last connection started and the backoff from when it
/// ended, so the floor binds only where no backoff applies: after a connection that delivered
/// audio or ended offline, and once the network returns.
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

  /// Starts the count and the waits over on a new Load: a track change, or a restart from the
  /// terminal error. Never on a retry's reload.
  mutating func reset() {
    deliveredNothingInARow = 0
    lastStartedAtMs = nil
    nextConnectMs = nil
  }

  /// A play intent that comes up starts the count and the waits over; a repeat while up does not.
  mutating func playIntentChanged(_ up: Bool) {
    if up, !playIntent { reset() }
    playIntent = up
  }

  /// The network returning drops what remains of the wait, keeping the floor, so the next connect
  /// goes out at once; the backoff still grows from where it was. A repeat while online does
  /// nothing.
  mutating func onlineChanged(_ online: Bool) {
    if online, !self.online { nextConnectMs = lastStartedAtMs.map { $0 + Self.minConnectIntervalMs } }
    self.online = online
  }

  /// Records how a connection ended. One that ends while offline does not grow the backoff: it
  /// delivered nothing for want of a network, not of a source. A stall the watch gives up on
  /// reports what its connection delivered.
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
