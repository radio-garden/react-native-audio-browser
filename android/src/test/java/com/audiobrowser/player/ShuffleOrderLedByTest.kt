package com.audiobrowser.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuffleOrderLedByTest {
  @Test
  fun `the start index leads the order`() {
    for (start in 0 until 6) {
      val order = shuffleOrderLedBy(startIndex = start, count = 6)
      assertEquals("start index must be at shuffle position 0", start, order.firstIndex)
      assertNotEquals(
        "a queue of 6 must have somewhere to advance to from its start track",
        C.INDEX_UNSET.toLong(),
        order.getNextIndex(start).toLong(),
      )
    }
  }

  @Test
  fun `every index appears exactly once`() {
    val order = shuffleOrderLedBy(startIndex = 3, count = 8)
    assertEquals(8, order.length)
    val seen = mutableListOf<Int>()
    var index = order.firstIndex
    while (index != C.INDEX_UNSET) {
      seen.add(index)
      index = order.getNextIndex(index)
    }
    assertEquals((0 until 8).toList(), seen.sorted())
  }

  @Test
  fun `a single-track queue leads with its only track and ends there`() {
    val order = shuffleOrderLedBy(startIndex = 0, count = 1)
    assertEquals(0, order.firstIndex)
    assertEquals(C.INDEX_UNSET, order.getNextIndex(0))
  }

  @Test
  fun `an out-of-range start index is clamped rather than throwing`() {
    assertEquals(4, shuffleOrderLedBy(startIndex = 99, count = 5).firstIndex)
    assertEquals(0, shuffleOrderLedBy(startIndex = -3, count = 5).firstIndex)
  }

  @Test
  fun `an empty queue produces an empty order`() {
    assertEquals(0, shuffleOrderLedBy(startIndex = 0, count = 0).length)
  }

  @Test
  fun `the tail is actually shuffled`() {
    // Draws may repeat a tail; 50 identical draws out of 7! tails all but prove it is unshuffled.
    val tails =
      (0 until 50)
        .map { shuffleOrderLedBy(startIndex = 0, count = 8) }
        .map { order ->
          buildList {
            var index = order.getNextIndex(order.firstIndex)
            while (index != C.INDEX_UNSET) {
              add(index)
              index = order.getNextIndex(index)
            }
          }
        }
        .toSet()
    assertTrue("50 draws produced one identical tail — the order is not shuffled", tails.size > 1)
  }
}
