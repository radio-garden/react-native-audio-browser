package com.audiobrowser.remote

import android.content.Context
import androidx.annotation.MainThread
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.RemoteOutputs
import com.audiobrowser.cast.castRouteCategory
import com.audiobrowser.sonos.SonosOutputs
import com.margelo.nitro.audiobrowser.OutputType
import com.margelo.nitro.audiobrowser.RemoteOutputsOptions
import com.margelo.nitro.audiobrowser.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/** What a receiver fetches for a track: its media URL, after the media transform, and artwork. */
internal data class RemoteUrls(val url: String, val artworkUrl: String?)

/**
 * Sets up the Output Switcher's routes and the Sonos route provider as [RemoteOutputsOptions] says,
 * and owns the remote of each output turned on and the [RemoteSwitchingPlayer] that plays on them,
 * resolving the items it sends through [resolveUrls].
 */
internal class RemoteOutputsController(
  private val context: Context,
  private val resolveUrls: suspend (Track) -> RemoteUrls,
  private val castRemote: (Context, String?) -> RemotePlayback?,
  private val sonosRemote: (Context, CoroutineScope) -> RemotePlayback,
  /** Whether the phone's idle engine is the retry loop's, between connections of a load. */
  private val localHeld: () -> Boolean = { false },
) {
  private val scope = MainScope()
  private var options: RemoteOutputsOptions? = null
  private var cast: RemotePlayback? = null
  private var sonos: RemotePlayback? = null

  /** Plays while any output is on; null when none is. */
  var player: RemoteSwitchingPlayer? = null
    private set

  /**
   * Turns the outputs in [options] on and the rest off; null turns remote outputs off. An output
   * that stays on keeps its remote, session and what it plays, even if the receiver ID changes.
   * [local] is used only when the switch is built. Returns whether [player] changed.
   */
  @MainThread
  fun apply(options: RemoteOutputsOptions?, local: MediaPlayer): Boolean {
    val wantCast = options?.cast == true
    val wantSonos = options?.sonos == true
    val receiver = options?.castReceiverApplicationId
    val types = buildMap {
      castRouteCategory(receiver)?.takeIf { wantCast }?.let { put(it, OutputType.CAST) }
      if (wantSonos) put(SonosOutputs.CATEGORY, OutputType.SONOS)
    }
    RemoteOutputs.setEnabled(context, options != null, types)
    SonosOutputs.setEnabled(context, wantSonos)
    val had = this.options
    this.options = options
    // A remote turned off is only dropped here; the switch releases it below.
    if (wantCast != (had?.cast == true))
      cast = if (wantCast) castRemote(context, receiver) else null
    if (wantSonos != (had?.sonos == true))
      sonos = if (wantSonos) sonosRemote(context, scope) else null
    val remotes = listOfNotNull(cast, sonos)
    val previous = player
    player =
      when {
        remotes.isEmpty() -> null.also { previous?.release() }
        previous == null ->
          RemoteSwitchingPlayer(local, remotes, ::resolve, scope, localHeld = localHeld)
        else -> previous.also { it.setRemotes(remotes) }
      }
    return player !== previous
  }

  fun setLocal(local: MediaPlayer) {
    player?.setLocal(local)
  }

  /**
   * Brings playback back to the local player and lets go of the remotes; the controller is unusable
   * after. The Output Switcher routes and Sonos route provider stay as [apply] left them.
   */
  fun release() {
    player?.release()
    player = null
    cast = null
    sonos = null
    options = null
    scope.cancel()
  }

  /** [item] as a receiver loads it; an item without a [Track] tag plays its own URI. */
  private suspend fun resolve(item: MediaItem): MediaItem {
    val track = item.localConfiguration?.tag as? Track
    val urls =
      if (track != null) withContext(Dispatchers.IO) { resolveUrls(track) }
      else RemoteUrls(checkNotNull(item.localConfiguration?.uri) { "No URL" }.toString(), null)
    return remoteItemOf(item, urls.url, urls.artworkUrl, live = track?.live == true)
  }
}
