package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.RepeatMode

/**
 * Player state set before the player exists, by setup options or the setters; last write wins.
 * [applyTo] consumes it, so a later re-setup does not replay it.
 */
internal class PendingPlayerState {
  var playWhenReady: Boolean? = null
  var repeatMode: RepeatMode? = null
  var shuffleEnabled: Boolean? = null

  fun applyTo(player: Player) {
    repeatMode?.let { player.repeatMode = it }
    shuffleEnabled?.let { player.shuffleMode = it }
    playWhenReady?.let { player.applySetupPlayWhenReady(it) }
    playWhenReady = null
    repeatMode = null
    shuffleEnabled = null
  }
}
