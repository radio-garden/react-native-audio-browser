package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.FailedRequestCount
import com.margelo.nitro.audiobrowser.FailedRequestKind
import java.util.concurrent.ConcurrentHashMap

/**
 * Every [Player.awaitBrowser] timeout in this process since the last [take], by kind. Process-wide,
 * not per player: the service, and its player, can be made again before JS first registers a
 * browser.
 */
internal object RegistrationTimeouts {
  private val counts = ConcurrentHashMap<FailedRequestKind, Int>()

  fun record(kind: FailedRequestKind) {
    counts.merge(kind, 1, Int::plus)
  }

  /** The counts, leaving out kinds with none, and clears them. */
  fun take(): Array<FailedRequestCount> =
    counts.keys
      .mapNotNull { kind -> counts.remove(kind)?.let { FailedRequestCount(kind, it.toDouble()) } }
      .toTypedArray()
}
