package com.audiobrowser.destination.sonos

import androidx.media3.common.Player

/** A UPnP transport state mapped onto the Media3 facts a player exposes. */
data class MappedTransportState(val playbackState: Int, val isPlaying: Boolean)

/**
 * Maps a UPnP `CurrentTransportState` to a Media3 playback state + isPlaying. Live streams have no
 * finite end, so `STOPPED`/`NO_MEDIA_PRESENT` map to `STATE_IDLE` (not `STATE_ENDED`). Unknown
 * states are treated as idle.
 */
object TransportStateMapper {
  fun map(upnpState: String): MappedTransportState =
    when (upnpState.uppercase()) {
      "PLAYING" -> MappedTransportState(Player.STATE_READY, isPlaying = true)
      "PAUSED_PLAYBACK", "PAUSED_RECORDING" ->
        MappedTransportState(Player.STATE_READY, isPlaying = false)
      "TRANSITIONING" -> MappedTransportState(Player.STATE_BUFFERING, isPlaying = false)
      "STOPPED", "NO_MEDIA_PRESENT" -> MappedTransportState(Player.STATE_IDLE, isPlaying = false)
      else -> MappedTransportState(Player.STATE_IDLE, isPlaying = false)
    }
}
