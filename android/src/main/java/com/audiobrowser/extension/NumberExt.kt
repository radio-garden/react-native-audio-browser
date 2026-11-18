package com.audiobrowser.extension

class NumberExt {
  companion object {
    /** Milliseconds to seconds. */
    fun Number.toSeconds(): Double {
      return this.toDouble() / 1000
    }

    /** Seconds to milliseconds, truncated. */
    fun Number.toMilliseconds(): Long {
      return (this.toDouble() * 1000).toLong()
    }
  }
}
