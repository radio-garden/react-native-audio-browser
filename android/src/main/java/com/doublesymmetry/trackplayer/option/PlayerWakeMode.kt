package com.doublesymmetry.trackplayer.option

import androidx.media3.common.C

enum class PlayerWakeMode {
  NONE,
  LOCAL,
  NETWORK;

  fun toMedia3(): Int {
    return when (this) {
      NONE -> C.WAKE_MODE_NONE
      LOCAL -> C.WAKE_MODE_LOCAL
      NETWORK -> C.WAKE_MODE_NETWORK
    }
  }
}
