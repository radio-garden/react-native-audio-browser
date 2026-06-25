package com.audiobrowser.cast

/**
 * Pure ref-counter for Cast device-discovery leases (held by mounted `useCastState()` hooks via
 * `retainCastDiscovery()`/`releaseCastDiscovery()`). Active scanning is expensive, so it runs only
 * while at least one lease is held; this class owns the counting and reports the 0↔1 edges, while
 * the `cast` sourceset performs the actual `MediaRouter` start/stop scan on those edges.
 *
 * Lives in **main** (no Cast SDK) so it compiles in the default build and is unit-testable. Not
 * thread-safe — the controller confines all calls to the main thread.
 */
class CastDiscoveryLeases {
  var count: Int = 0
    private set

  /** Records a lease; returns true on the 0→1 edge (caller should START the scan). */
  fun retain(): Boolean {
    count++
    return count == 1
  }

  /**
   * Releases a lease (never below zero); returns true on the 1→0 edge (caller should STOP the
   * scan). Returns false for a redundant release at zero.
   */
  fun release(): Boolean {
    if (count == 0) return false
    count--
    return count == 0
  }

  /** Resets to zero (e.g. on full teardown). */
  fun reset() {
    count = 0
  }
}
