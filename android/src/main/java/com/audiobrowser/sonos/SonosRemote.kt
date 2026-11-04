package com.audiobrowser.sonos

import android.content.Context
import android.media.MediaRouter2
import android.os.Build
import com.audiobrowser.remote.RemotePlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * The Sonos room selected in the Output Switcher ([SonosOutputs.selected]) as a remote output: a
 * session while one is selected, with a [SonosPlayer] of its own. Selecting another room starts its
 * session in place of the last, so playback moves from room to room.
 */
internal class SonosRemote(
  private val context: Context,
  private val scope: CoroutineScope,
  private val controlOf: (SonosRoom) -> SonosControl = defaultControl(),
  private val routingControllerId: () -> String? = systemRoutingControllerId(context),
) : RemotePlayback {
  private var room: SonosRoom? = null
  private var session: SonosPlayer? = null
  private var listener: RemotePlayback.Listener? = null
  private val onSelectionChanged: () -> Unit = ::follow

  init {
    SonosOutputs.addListener(onSelectionChanged)
    follow()
  }

  override val player: SonosPlayer
    get() = checkNotNull(session) { "No Sonos room is selected" }

  override val isSessionAvailable: Boolean
    get() = session != null

  override fun setListener(listener: RemotePlayback.Listener?) {
    this.listener = listener
  }

  override fun release() {
    SonosOutputs.removeListener(onSelectionChanged)
    end(session, room)
    session = null
    room = null
  }

  private fun follow() {
    val selected = SonosOutputs.selected
    if (selected == room) return
    val ended = session
    val endedRoom = room
    room = selected
    session = selected?.let(::start)
    listener?.onSessionChanged()
    end(ended, endedRoom)
  }

  private fun start(room: SonosRoom): SonosPlayer {
    lateinit var started: SonosPlayer
    started =
      SonosPlayer(
        context.mainLooper,
        controlOf(room),
        onEnded = { if (session === started) listener?.onEnded() },
        onError = { if (session === started) listener?.onFailed(it) },
        onVolume = { SonosOutputs.reportVolume(room.id, it) },
        routingControllerId = routingControllerId,
        scope = scope,
        io = Dispatchers.IO,
      )
    SonosOutputs.setSession(room.id) { started.setDeviceVolume(it, 0) }
    return started
  }

  private fun end(player: SonosPlayer?, room: SonosRoom?) {
    room?.let { SonosOutputs.setSession(it.id, null) }
    player?.release()
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
