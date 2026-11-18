package com.audiobrowser.sonos

import android.content.Context
import android.net.wifi.WifiManager
import androidx.annotation.VisibleForTesting
import androidx.mediarouter.media.MediaRouteDiscoveryRequest
import androidx.mediarouter.media.MediaRouteProvider
import androidx.mediarouter.media.MediaRouteProviderDescriptor
import androidx.mediarouter.media.MediaRouteProviderService
import androidx.mediarouter.media.MediaRouter
import com.audiobrowser.RemoteOutputs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Serves the Sonos rooms as media routes for the system Output Switcher. The system binds it, not
 * the app: [SonosOutputs.setEnabled] turns the component on.
 */
class SonosRouteProviderService : MediaRouteProviderService() {
  private var provider: SonosRouteProvider? = null

  override fun onCreateMediaRouteProvider(): MediaRouteProvider =
    SonosRouteProvider(this).also { provider = it }

  override fun onDestroy() {
    provider?.release()
    super.onDestroy()
  }
}

@VisibleForTesting
internal class SonosRouteProvider(context: Context) : MediaRouteProvider(context) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val discoverer =
    SonosDiscoverer(sonosHttpClient, SsdpDiscovery(wifiMulticastLock(context.applicationContext)))
  private val soap = SoapClient(sonosHttpClient)

  private var rooms: List<SonosRoom> = listOfNotNull(SonosOutputs.selected)
  private var lastScanMs = 0L
  private var scanJob: Job? = null
  private val onVolumeChanged: () -> Unit = ::publish

  init {
    SonosOutputs.addVolumeListener(onVolumeChanged)
    // After the system rebinds the service, MediaRouter2 looks up the room still selected in this
    // descriptor before it creates the room's session.
    publish()
  }

  override fun onDiscoveryRequestChanged(request: MediaRouteDiscoveryRequest?) {
    scanJob?.cancel()
    scanJob = null
    if (request == null || !request.selector.hasControlCategory(RemoteOutputs.CATEGORY)) return
    scanJob =
      scope.launch {
        // Passive requests reuse a recent scan; the open switcher asks for an active one.
        if (!request.isActiveScan && System.currentTimeMillis() - lastScanMs < PASSIVE_RESCAN_MS) {
          return@launch
        }
        while (isActive) {
          val found = withContext(Dispatchers.IO) { discoverer.discover() }
          lastScanMs = System.currentTimeMillis()
          // SSDP is lossy: the selected room stays listed when one scan misses it.
          val selected = SonosOutputs.selected
          rooms =
            if (selected != null && found.none { it.id == selected.id }) found + selected else found
          publish()
          if (!request.isActiveScan) break
          delay(ACTIVE_RESCAN_MS)
        }
      }
  }

  fun release() {
    SonosOutputs.removeVolumeListener(onVolumeChanged)
    scope.cancel()
  }

  /**
   * Serves only this app's own sessions: [SonosOutputs.selected] moves this app's playback, so
   * another app's session would play this app's queue in its place.
   */
  override fun onCreateRouteController(
    routeId: String,
    options: RouteControllerOptions,
  ): RouteController? {
    if (options.clientPackageName != context.packageName) {
      Timber.d("Sonos: refusing a session for %s", options.clientPackageName)
      return null
    }
    val room = rooms.firstOrNull { it.id == routeId } ?: return null
    return SonosRouteController(room)
  }

  private fun publish() {
    descriptor = sonosRouteDescriptors(rooms, SonosOutputs.selected?.id, SonosOutputs::volumeOf)
    Timber.d("Sonos: %d room(s)", rooms.size)
  }

  /** Reads [room]'s volume from the speaker, for a room no session plays on: a session reads it. */
  private suspend fun readVolume(room: SonosRoom): Int? {
    val volume =
      withContext(Dispatchers.IO) {
        runCatching { SonosTransport(room.device, soap).getVolume() }.getOrNull()
      } ?: return null
    SonosOutputs.reportVolume(room.id, volume)
    return volume
  }

  private fun setVolume(room: SonosRoom, volume: Int) {
    if (SonosOutputs.setVolumeThroughSession(room.id, volume)) return
    SonosOutputs.reportVolume(room.id, volume)
    scope.launch(Dispatchers.IO) {
      runCatching { SonosTransport(room.device, soap).setVolume(volume) }
        .onFailure { Timber.w(it, "Sonos: failed to set the volume of %s", room.name) }
    }
  }

  private inner class SonosRouteController(private val room: SonosRoom) : RouteController() {
    override fun onSelect() {
      SonosOutputs.select(room)
      publish()
      if (!SonosOutputs.hasSession(room.id)) scope.launch { readVolume(room) }
    }

    override fun onUnselect(reason: Int) {
      SonosOutputs.unselect(room)
      publish()
    }

    override fun onSetVolume(volume: Int) = setVolume(room, volume.coerceIn(0, MAX_VOLUME))

    override fun onUpdateVolume(delta: Int) {
      scope.launch {
        // Read from the speaker unless a session tracks it: the Sonos app or the speaker's buttons
        // may have moved it.
        val current =
          (if (SonosOutputs.hasSession(room.id)) null else readVolume(room))
            ?: SonosOutputs.volumeOf(room.id)
            ?: return@launch
        setVolume(room, (current + delta).coerceIn(0, MAX_VOLUME))
      }
    }

    override fun onRelease() {}
  }

  private companion object {
    const val ACTIVE_RESCAN_MS = 10_000L
    const val PASSIVE_RESCAN_MS = 60_000L
  }
}

private const val MAX_VOLUME = 100

@VisibleForTesting
internal fun sonosRouteDescriptors(
  rooms: List<SonosRoom>,
  selectedId: String?,
  volumeOf: (String) -> Int?,
): MediaRouteProviderDescriptor {
  val builder = MediaRouteProviderDescriptor.Builder()
  for (room in rooms) {
    builder.addRoute(
      RemoteOutputs.routeDescriptorBuilder(room.id, room.name, SonosOutputs.CATEGORY)
        .setDescription("Sonos")
        .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
        .setDeviceType(MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER)
        .setVolumeHandling(MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
        .setVolumeMax(MAX_VOLUME)
        .setVolume(volumeOf(room.id) ?: 0)
        .setConnectionState(
          if (room.id == selectedId) MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED
          else MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED
        )
        .setEnabled(true)
        .build()
    )
  }
  return builder.build()
}

@VisibleForTesting
internal fun wifiMulticastLock(context: Context): MulticastLockHandle? {
  if (!SonosOutputs.canHoldMulticastLock(context)) return null
  val lock =
    (context.getSystemService(Context.WIFI_SERVICE) as WifiManager)
      .createMulticastLock("audiobrowser-sonos")
      .apply { setReferenceCounted(true) }
  return object : MulticastLockHandle {
    override fun acquire() = lock.acquire()

    override fun release() = lock.release()
  }
}
