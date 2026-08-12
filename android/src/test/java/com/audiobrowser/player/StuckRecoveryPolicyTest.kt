package com.audiobrowser.player

import org.junit.Assert.assertEquals
import org.junit.Test

class StuckRecoveryPolicyTest {
  @Test
  fun `first stuck event recovers`() {
    val p = StuckRecoveryPolicy(maxRecoveries = 3)
    assertEquals(StuckRecoveryPolicy.Decision.RECOVER, p.onStuck())
  }

  @Test
  fun `recovers up to max then gives up`() {
    val p = StuckRecoveryPolicy(maxRecoveries = 3)
    assertEquals(StuckRecoveryPolicy.Decision.RECOVER, p.onStuck()) // 1
    assertEquals(StuckRecoveryPolicy.Decision.RECOVER, p.onStuck()) // 2
    assertEquals(StuckRecoveryPolicy.Decision.RECOVER, p.onStuck()) // 3
    assertEquals(StuckRecoveryPolicy.Decision.GIVE_UP, p.onStuck()) // 4 -> exhausted
  }

  @Test
  fun `give up is sticky and does not self-refill`() {
    val p = StuckRecoveryPolicy(maxRecoveries = 3)
    repeat(3) { p.onStuck() }
    assertEquals(StuckRecoveryPolicy.Decision.GIVE_UP, p.onStuck()) // 4
    assertEquals(StuckRecoveryPolicy.Decision.GIVE_UP, p.onStuck()) // still GIVE_UP, no refill
  }

  @Test
  fun `reset refills the budget`() {
    val p = StuckRecoveryPolicy(maxRecoveries = 3)
    repeat(4) { p.onStuck() } // now exhausted
    p.reset()
    assertEquals(StuckRecoveryPolicy.Decision.RECOVER, p.onStuck()) // 1
    assertEquals(StuckRecoveryPolicy.Decision.RECOVER, p.onStuck()) // 2
    assertEquals(StuckRecoveryPolicy.Decision.RECOVER, p.onStuck()) // 3
    assertEquals(StuckRecoveryPolicy.Decision.GIVE_UP, p.onStuck()) // 4
  }
}
