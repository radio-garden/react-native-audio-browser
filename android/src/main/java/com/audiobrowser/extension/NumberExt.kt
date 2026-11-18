package com.audiobrowser.extension

class NumberExt {
  companion object {
    /** Reads the receiver as milliseconds. */
    fun Number.toSeconds(): Double {
      return this.toDouble() / 1000
    }

    /** Reads the receiver as seconds; truncates toward zero. */
    fun Number.toMilliseconds(): Long {
      return (this.toDouble() * 1000).toLong()
    }
  }
}
