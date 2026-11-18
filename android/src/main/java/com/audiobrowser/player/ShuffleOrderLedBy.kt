package com.audiobrowser.player

import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * A shuffle order over [count] items with [startIndex] (clamped into range) first and the rest in
 * random order. [random] only seeds the order's later reshuffles on insert.
 *
 * Media3's `cloneAndSet` discards the start index, so the starting track would land at a random
 * position. Apply it whether or not shuffle is on: enabling shuffle later keeps the order.
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
