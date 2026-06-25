import Foundation

/// Pure per-item bookkeeping for the bounded, reactive re-signing of stale Cast
/// queue URLs (see ADR-0003): caps attempts per receiver item id and dedups
/// re-entrant triggers while a re-sign is in flight, so a genuinely dead stream
/// surfaces a real error instead of looping forever.
///
/// **Cast-SDK-free and UNGATED on purpose** — it touches only `UInt` item ids and
/// so compiles in the default (Cast-disabled) build and is unit-testable from the
/// SPM test target (mirrors Android's `CastReSignBudget` in the `main` sourceset).
/// The gated `CastReSign` keeps the GCK resolve / `queueUpdate` calls and delegates
/// all bookkeeping here. A value type confined to the main actor by its single
/// owner; not itself thread-safe.
struct CastReSignBudget {
  /// The outcome of asking whether to re-sign a failing item.
  enum Decision {
    /// Dispatch a re-sign; an attempt has been claimed + marked in flight. Call
    /// `markDone` when the dispatch settles.
    case attempt
    /// A re-sign for this item is already running — do nothing (the caller must
    /// NOT surface a terminal error yet).
    case inFlight
    /// The per-item cap is spent — the caller surfaces the real error.
    case exhausted
  }

  private let maxAttemptsPerItem: Int
  private var attemptsByItem: [UInt: Int] = [:]
  private var inFlight: Set<UInt> = []

  init(maxAttemptsPerItem: Int = 3) {
    self.maxAttemptsPerItem = maxAttemptsPerItem
  }

  /// Decides whether to dispatch a re-sign for `itemID` and, when yes, claims an
  /// attempt + marks it in flight (so re-entrant idle+error callbacks for the same
  /// item don't each burn the budget).
  mutating func shouldAttempt(_ itemID: UInt) -> Decision {
    if inFlight.contains(itemID) { return .inFlight }
    let used = attemptsByItem[itemID] ?? 0
    if used >= maxAttemptsPerItem { return .exhausted }
    attemptsByItem[itemID] = used + 1
    inFlight.insert(itemID)
    return .attempt
  }

  /// Whether a re-sign for `itemID` is currently dispatched and not yet settled.
  /// Lets a caller short-circuit a repeat trigger before doing any other work.
  func isInFlight(_ itemID: UInt) -> Bool {
    inFlight.contains(itemID)
  }

  /// Clears the in-flight mark for `itemID` once its dispatched re-sign settles
  /// (success or failure), so a later genuine expiry can be re-signed again.
  mutating func markDone(_ itemID: UInt) {
    inFlight.remove(itemID)
  }

  /// Resets all counters — call on a fresh queue load / new Cast session.
  mutating func reset() {
    attemptsByItem.removeAll()
    inFlight.removeAll()
  }
}
