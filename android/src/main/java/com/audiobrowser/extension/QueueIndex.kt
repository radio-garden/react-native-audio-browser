package com.audiobrowser.extension

import kotlin.math.truncate

/**
 * The queue index a JS number names, its fraction dropped.
 *
 * @throws IllegalArgumentException for a number no `Int` holds: NaN, infinite, or beyond `Int`'s
 *   range.
 */
fun Double.toQueueIndex(): Int {
  val whole = truncate(this)
  if (whole.isNaN() || whole < Int.MIN_VALUE || whole > Int.MAX_VALUE) {
    throw IllegalArgumentException(
      "Invalid track index: index must be a finite number within the range of Int, got $this"
    )
  }
  return whole.toInt()
}
