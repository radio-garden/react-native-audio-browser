package com.audiobrowser.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ref-count edges for [CastDiscoveryLeases]: start on 0→1, stop on 1→0, no under/over count. */
class CastDiscoveryLeasesTest {

  @Test
  fun `first retain reports the start edge, subsequent ones do not`() {
    val leases = CastDiscoveryLeases()
    assertTrue("0->1 should signal start", leases.retain())
    assertFalse("1->2 should not", leases.retain())
    assertFalse("2->3 should not", leases.retain())
    assertEquals(3, leases.count)
  }

  @Test
  fun `last release reports the stop edge, earlier ones do not`() {
    val leases = CastDiscoveryLeases()
    leases.retain()
    leases.retain()
    leases.retain()
    assertFalse("3->2 should not signal stop", leases.release())
    assertFalse("2->1 should not", leases.release())
    assertTrue("1->0 should signal stop", leases.release())
    assertEquals(0, leases.count)
  }

  @Test
  fun `release at zero is a no-op and never goes negative`() {
    val leases = CastDiscoveryLeases()
    assertFalse(leases.release())
    assertEquals(0, leases.count)
    assertFalse(leases.release())
    assertEquals(0, leases.count)
  }

  @Test
  fun `retain after returning to zero signals start again`() {
    val leases = CastDiscoveryLeases()
    leases.retain()
    leases.release()
    assertTrue("0->1 again should signal start", leases.retain())
  }

  @Test
  fun `reset clears the count`() {
    val leases = CastDiscoveryLeases()
    leases.retain()
    leases.retain()
    leases.reset()
    assertEquals(0, leases.count)
    // After reset, the next retain is a fresh start edge.
    assertTrue(leases.retain())
  }
}
