package com.audiobrowser.model

enum class AppKilledPlaybackBehavior(val string: String) {
  CONTINUE_PLAYBACK("continue-playback"),
  PAUSE_PLAYBACK("pause-playback"),
  STOP_PLAYBACK_AND_REMOVE_NOTIFICATION("stop-playback-and-remove-notification"),
}
