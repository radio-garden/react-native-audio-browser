package com.audiobrowser.sonos

import android.content.Context
import android.media.MediaRouter2
import android.os.Build
import androidx.media3.common.PlaybackException
import com.audiobrowser.remote.RemoteMediaResolutions
import com.audiobrowser.remote.RemotePlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * The Sonos room selected in the Output Switcher ([SonosOutputs.selected]) as a remote output: a
 * session while one is selected, with a [SonosPlayer] of its own. Selecting another room ends the
 * session before the next one starts, so playback moves through the phone's state.
 */
internal class SonosRemote(
  private val context: Context,
  private val resolutions: RemoteMediaResolutions,
  private val scope: CoroutineScope,
  private val controlOf: (SonosRoom) -> SonosControl = defaultControl(),
  private val routingControllerId: () -> String? = systemRoutingControllerId(context),
) : RemotePlayback {
  private var room: SonosRoom? = null
  private var session: SonosPlayer? = null
  private var sessionListener: (() -> Unit)? = null
  private var errorListener: ((PlaybackException) -> Unit)? = null
  private val onSelectionChanged: () -> Unit = ::follow

  init {
    SonosOutputs.addListener(onSelectionChanged)
    follow()
  }

  override val player: SonosPlayer
    get() = checkNotNull(session) { "No Sonos room is selected" }

  override val isSessionAvailable: Boolean
    get() = session != null

  override fun setSessionListener(listener: (() -> Unit)?) {
    sessionListener = listener
  }

  override fun setErrorListener(listener: ((PlaybackException) -> Unit)?) {
    errorListener = listener
  }

  override fun release() {
    SonosOutputs.removeListener(onSelectionChanged)
    session?.release()
    session = null
    room = null
  }

  private fun follow() {
    val selected = SonosOutputs.selected
    if (selected == room) return
    session?.let { ended ->
      session = null
      room = null
      sessionListener?.invoke()
      ended.release()
    }
    if (selected != null) {
      room = selected
      session =
        SonosPlayer(
          context.mainLooper,
          controlOf(selected),
          mediaOf = resolutions::get,
          onError = { errorListener?.invoke(it) },
          routingControllerId = routingControllerId,
          scope = scope,
          io = Dispatchers.IO,
        )
      sessionListener?.invoke()
    }
  }

  private companion object {
    /**
     * The system's routing session for the selected room, which ties the media session to it:
     * without it, the notification's output chip can't open the Output Switcher to move playback
     * back.
     */
    fun systemRoutingControllerId(context: Context): () -> String? {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return { null }
      val router by lazy { MediaRouter2.getInstance(context) }
      return { router.controllers.firstOrNull { it != router.systemController }?.id }
    }

    fun defaultControl(): (SonosRoom) -> SonosControl {
      val soap = SoapClient(sonosHttpClient)
      return { room -> SonosTransport(room.device, soap) }
    }
  }
}
