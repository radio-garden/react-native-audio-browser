package com.audiobrowser.destination

import com.audiobrowser.browser.resolveArtworkUrl
import com.audiobrowser.browser.resolveMediaUrl
import com.audiobrowser.player.Player
import com.margelo.nitro.audiobrowser.MediaResolveTarget
import com.margelo.nitro.audiobrowser.Track

/**
 * Resolves a [Track]'s media + artwork URLs for a remote playback destination (`target:'cast'`): the
 * device fetches the bytes itself, so both must be self-contained (query-signed) — request headers
 * do not cross. Shared by the Cast and Sonos backends so this resolution lives in ONE place (a
 * missing artwork resolution in a hand-copied version previously shipped as a Sonos bug). Each
 * backend builds its own platform `MediaItem` from the result.
 */
object RemoteTrackResolver {
  data class Resolved(val mediaUri: String, val track: Track)

  suspend fun resolve(player: Player, track: Track): Resolved {
    val browserManager = player.browser?.browserManager
    val mediaUri =
      track.src?.let { src ->
        runCatching { browserManager?.resolveMediaUrl(src, MediaResolveTarget.CAST) }.getOrNull()?.path
          ?: src
      } ?: ""
    val artworkTrack =
      runCatching {
          val artwork = browserManager?.resolveArtworkUrl(track, null, null, MediaResolveTarget.CAST)
          if (artwork?.uri?.isNotEmpty() == true) track.copy(artwork = artwork.uri) else track
        }
        .getOrDefault(track)
    return Resolved(mediaUri, artworkTrack)
  }
}
