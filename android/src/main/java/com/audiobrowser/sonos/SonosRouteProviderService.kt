package com.audiobrowser.sonos

import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.annotation.VisibleForTesting
import androidx.mediarouter.media.MediaRouteDescriptor
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
 * Serves the Sonos rooms as media routes, so the system Output Switcher lists them. Bound by the
 * system, not the app: [SonosOutputs.setEnabled] turns the component on.
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

private class SonosRouteProvider(context: Context) : MediaRouteProvider(context) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val httpClient = sonosHttpClient()
  private val discoverer =
    SonosDiscoverer(httpClient, SsdpDiscovery(wifiMulticastLock(context.applicationContext)))
  private val soap = SoapClient(httpClient)

  private var rooms: List<SonosRoom> = emptyList()
  private var lastScanMs = 0L
  private var scanJob: Job? = null
  private val volumes = HashMap<String, Int>()

  override fun onDiscoveryRequestChanged(request: MediaRouteDiscoveryRequest?) {
    scanJob?.cancel()
    scanJob = null
    if (request == null || !request.selector.hasControlCategory(RemoteOutputs.CATEGORY)) return
    scanJob =
      scope.launch {
        // Passive discovery keeps what a recent scan found; the open switcher asks for active.
        if (!request.isActiveScan && System.currentTimeMillis() - lastScanMs < PASSIVE_RESCAN_MS) {
          return@launch
        }
        while (isActive) {
          val found = withContext(Dispatchers.IO) { discoverer.discover() }
          lastScanMs = System.currentTimeMillis()
          // A room selected now stays listed when one scan misses it: SSDP is lossy.
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
    scope.cancel()
  }

  override fun onCreateRouteController(routeId: String): RouteController? {
    val room = rooms.firstOrNull { it.id == routeId } ?: return null
    return SonosRouteController(room)
  }

  private fun publish() {
    descriptor = sonosRouteDescriptors(rooms, SonosOutputs.selected?.id, volumes)
    Timber.d("Sonos: %d room(s)", rooms.size)
  }

  private fun setVolume(room: SonosRoom, volume: Int) {
    volumes[room.id] = volume
    publish()
    scope.launch(Dispatchers.IO) {
      runCatching { SonosTransport(room.device, soap).setVolume(volume) }
        .onFailure { Timber.w(it, "Sonos: failed to set the volume of %s", room.name) }
    }
  }

  private inner class SonosRouteController(private val room: SonosRoom) : RouteController() {
    override fun onSelect() {
      SonosOutputs.select(room)
      publish()
      scope.launch {
        val volume =
          withContext(Dispatchers.IO) {
            runCatching { SonosTransport(room.device, soap).getVolume() }.getOrNull()
          }
        if (volume != null && SonosOutputs.selected?.id == room.id) {
          volumes[room.id] = volume
          publish()
        }
      }
    }

    override fun onUnselect(reason: Int) {
      SonosOutputs.unselect(room)
      publish()
    }

    override fun onSetVolume(volume: Int) = setVolume(room, volume.coerceIn(0, MAX_VOLUME))

    override fun onUpdateVolume(delta: Int) {
      scope.launch {
        // From the speaker's own volume: the Sonos app or the speaker's buttons may have moved it.
        val current =
          withContext(Dispatchers.IO) {
            runCatching { SonosTransport(room.device, soap).getVolume() }.getOrNull()
          } ?: volumes[room.id] ?: return@launch
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

/** The routes for [rooms]: the selected one connected, each in both output categories. */
@VisibleForTesting
internal fun sonosRouteDescriptors(
  rooms: List<SonosRoom>,
  selectedId: String?,
  volumes: Map<String, Int>,
): MediaRouteProviderDescriptor {
  val filter =
    IntentFilter().apply {
      addCategory(RemoteOutputs.CATEGORY)
      addCategory(SonosOutputs.CATEGORY)
    }
  val builder = MediaRouteProviderDescriptor.Builder()
  for (room in rooms) {
    builder.addRoute(
      MediaRouteDescriptor.Builder(room.id, room.name)
        .setDescription("Sonos")
        .addControlFilter(filter)
        .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
        .setDeviceType(MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER)
        .setVolumeHandling(MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
        .setVolumeMax(MAX_VOLUME)
        .setVolume(volumes[room.id] ?: 0)
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

private fun wifiMulticastLock(context: Context): MulticastLockHandle? {
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
