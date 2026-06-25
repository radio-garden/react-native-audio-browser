package com.audiobrowser.cast

import com.audiobrowser.cast.CastReSignBudget.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** Per-item attempt cap + in-flight dedup for [CastReSignBudget]. */
class CastReSignBudgetTest {

  @Test
  fun `caps at three attempts per item`() {
    val budget = CastReSignBudget(maxAttemptsPerItem = 3)
    // Each attempt must settle (markDone) before the next can be claimed.
    assertEquals(Decision.ATTEMPT, budget.shouldAttempt("k"))
    budget.markDone("k")
    assertEquals(Decision.ATTEMPT, budget.shouldAttempt("k"))
    budget.markDone("k")
    assertEquals(Decision.ATTEMPT, budget.shouldAttempt("k"))
    budget.markDone("k")
    // Fourth is over the cap.
    assertEquals(Decision.EXHAUSTED, budget.shouldAttempt("k"))
  }

  @Test
  fun `re-entrant trigger while in flight is deduped without burning an attempt`() {
    val budget = CastReSignBudget(maxAttemptsPerItem = 3)
    assertEquals(Decision.ATTEMPT, budget.shouldAttempt("k")) // attempt 1, now in flight
    assertEquals(Decision.IN_FLIGHT, budget.shouldAttempt("k")) // deduped
    assertEquals(Decision.IN_FLIGHT, budget.shouldAttempt("k")) // still deduped
    budget.markDone("k")
    // Only one attempt was consumed, so two more remain.
    assertEquals(Decision.ATTEMPT, budget.shouldAttempt("k"))
    budget.markDone("k")
    assertEquals(Decision.ATTEMPT, budget.shouldAttempt("k"))
    budget.markDone("k")
    assertEquals(Decision.EXHAUSTED, budget.shouldAttempt("k"))
  }

  @Test
  fun `budget is independent per item key`() {
    val budget = CastReSignBudget(maxAttemptsPerItem = 1)
    assertEquals(Decision.ATTEMPT, budget.shouldAttempt("a"))
    budget.markDone("a")
    assertEquals(Decision.EXHAUSTED, budget.shouldAttempt("a"))
    // A different item still has its own budget.
    assertEquals(Decision.ATTEMPT, budget.shouldAttempt("b"))
  }

  @Test
  fun `reset restores the full budget and clears in-flight`() {
    val budget = CastReSignBudget(maxAttemptsPerItem = 1)
    assertEquals(Decision.ATTEMPT, budget.shouldAttempt("k")) // claims the only attempt, in flight
    budget.reset()
    // After reset the item is neither in flight nor exhausted.
    assertEquals(Decision.ATTEMPT, budget.shouldAttempt("k"))
  }
}
