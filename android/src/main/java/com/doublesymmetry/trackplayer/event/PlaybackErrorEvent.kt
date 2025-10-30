package com.doublesymmetry.trackplayer.event

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/**
 * Event data for playback error. Matches TypeScript interface: { error?: { code: string, message:
 * string } }
 */
data class PlaybackErrorEvent(
  /** Optional error details. Null when error is resolved. */
  val error: ErrorDetails? = null
) {
  data class ErrorDetails(val code: String, val message: String)

  constructor(code: String, message: String) : this(ErrorDetails(code, message))

  fun toBridge(): WritableMap {
    return Arguments.createMap().apply {
      error?.let { err ->
        putMap(
          "error",
          Arguments.createMap().apply {
            putString("code", err.code)
            putString("message", err.message)
          },
        )
      }
    }
  }
}
