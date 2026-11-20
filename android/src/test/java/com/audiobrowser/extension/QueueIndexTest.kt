package com.audiobrowser.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QueueIndexTest {

  @Test
  fun `a number no Int holds throws`() {
    listOf(
        Double.NaN,
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        1e19,
        -1e19,
        Int.MAX_VALUE + 1.0,
        Int.MIN_VALUE - 1.0,
      )
      .forEach { number ->
        assertThrows("$number", IllegalArgumentException::class.java) { number.toQueueIndex() }
      }
  }

  @Test
  fun `a number an Int holds drops its fraction`() {
    assertEquals(0, 0.0.toQueueIndex())
    assertEquals(2, 2.0.toQueueIndex())
    assertEquals(1, 1.7.toQueueIndex())
    assertEquals(0, (-0.5).toQueueIndex())
    assertEquals(-1, (-1.0).toQueueIndex())
    assertEquals(Int.MAX_VALUE, (Int.MAX_VALUE + 0.5).toQueueIndex())
    assertEquals(Int.MIN_VALUE, (Int.MIN_VALUE - 0.5).toQueueIndex())
  }

  @Test
  fun `the message names the number`() {
    val error = assertThrows(IllegalArgumentException::class.java) { Double.NaN.toQueueIndex() }
    assertEquals(
      "Invalid track index: index must be a finite number within the range of Int, got NaN",
      error.message,
    )
  }
}
