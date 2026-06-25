import Testing

@testable import AudioBrowserTestable

/// Per-item attempt cap + in-flight dedup for `CastReSignBudget` (mirrors the
/// Android `CastReSignBudgetTest`).
@Suite("CastReSignBudget")
struct CastReSignBudgetTests {
  @Test func capsAtThreeAttemptsPerItem() {
    var budget = CastReSignBudget(maxAttemptsPerItem: 3)
    // Each attempt must settle (markDone) before the next can be claimed.
    #expect(budget.shouldAttempt(1) == .attempt)
    budget.markDone(1)
    #expect(budget.shouldAttempt(1) == .attempt)
    budget.markDone(1)
    #expect(budget.shouldAttempt(1) == .attempt)
    budget.markDone(1)
    // Fourth is over the cap.
    #expect(budget.shouldAttempt(1) == .exhausted)
  }

  @Test func reentrantTriggerWhileInFlightIsDedupedWithoutBurningAnAttempt() {
    var budget = CastReSignBudget(maxAttemptsPerItem: 3)
    #expect(budget.shouldAttempt(7) == .attempt) // attempt 1, now in flight
    #expect(budget.shouldAttempt(7) == .inFlight) // deduped
    #expect(budget.shouldAttempt(7) == .inFlight) // still deduped
    budget.markDone(7)
    // Only one attempt was consumed, so two more remain.
    #expect(budget.shouldAttempt(7) == .attempt)
    budget.markDone(7)
    #expect(budget.shouldAttempt(7) == .attempt)
    budget.markDone(7)
    #expect(budget.shouldAttempt(7) == .exhausted)
  }

  @Test func budgetIsIndependentPerItem() {
    var budget = CastReSignBudget(maxAttemptsPerItem: 1)
    #expect(budget.shouldAttempt(1) == .attempt)
    budget.markDone(1)
    #expect(budget.shouldAttempt(1) == .exhausted)
    // A different item still has its own budget.
    #expect(budget.shouldAttempt(2) == .attempt)
  }

  @Test func resetRestoresFullBudgetAndClearsInFlight() {
    var budget = CastReSignBudget(maxAttemptsPerItem: 1)
    #expect(budget.shouldAttempt(9) == .attempt) // claims the only attempt, in flight
    budget.reset()
    // After reset the item is neither in flight nor exhausted.
    #expect(budget.shouldAttempt(9) == .attempt)
  }

  @Test func isInFlightTracksTheDispatchedAttempt() {
    var budget = CastReSignBudget(maxAttemptsPerItem: 3)
    #expect(!budget.isInFlight(5))
    #expect(budget.shouldAttempt(5) == .attempt)
    #expect(budget.isInFlight(5)) // dispatched, not yet settled
    #expect(!budget.isInFlight(6)) // a different item is unaffected
    budget.markDone(5)
    #expect(!budget.isInFlight(5)) // settled
  }

  @Test func exhaustedItemStaysExhaustedAcrossRepeatedAsks() {
    var budget = CastReSignBudget(maxAttemptsPerItem: 1)
    #expect(budget.shouldAttempt(3) == .attempt)
    budget.markDone(3)
    #expect(budget.shouldAttempt(3) == .exhausted)
    #expect(budget.shouldAttempt(3) == .exhausted)
  }
}
