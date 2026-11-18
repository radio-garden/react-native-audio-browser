package com.audiobrowser.player

import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * A shuffle order over [count] items with [startIndex] (clamped into range) at shuffle position 0
 * and the rest in random order. [random] only seeds the order's later reshuffles on insert.
 *
 * Media3 discards the start index: `ShuffleOrder.cloneAndSet(insertionCount, startIndex)` defaults
 * to `cloneAndClear().cloneAndInsert(0, insertionCount)` and `DefaultShuffleOrder` does not
 * override it, so with shuffle on the starting track lands at a random position, and when it lands
 * last the queue ends after its first track. Apply it whether or not shuffle is on: enabling
 * shuffle later does not regenerate the order.
 */
internal fun shuffleOrderLedBy(startIndex: Int, count: Int, random: Random = Random): ShuffleOrder {
  val first = startIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
  val indices = IntArray(count)
  if (count > 0) {
    indices[0] = first
    ((0 until count) - first).shuffled().forEachIndexed { i, index -> indices[i + 1] = index }
  }
  return ShuffleOrder.DefaultShuffleOrder(indices, random.nextLong())
}
