package com.audiobrowser.destination.sonos

import android.content.Context
import android.content.IntentFilter
import androidx.mediarouter.media.MediaRouteDescriptor
import androidx.mediarouter.media.MediaRouteDiscoveryRequest
import androidx.mediarouter.media.MediaRouteProvider
import androidx.mediarouter.media.MediaRouteProviderDescriptor
import androidx.mediarouter.media.MediaRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * An AndroidX [MediaRouteProvider] that publishes discovered Sonos speakers as routes, so they
 * appear in the same system chooser as Google Cast. While MediaRouter requests active discovery (an
 * app holds a destination lease via `retainCastDiscovery()`), it runs a periodic SSDP scan via
 * [SonosDiscoverer] and republishes the route set.
 *
 * Route selection is delegated to the owning `SonosBackend` through the callbacks: [onRouteSelected]
 * builds the `SonosPlayer` and performs the player swap; [onRouteUnselected] hands back to the
 * phone; [onSetRouteVolume] routes a volume change to the speaker.
 */
class SonosMediaRouteProvider(
  context: Context,
  private val discoverer: SonosDiscoverer,
  private val scope: CoroutineScope,
  private val onRouteSelected: (SonosDevice) -> Unit,
  private val onRouteUnselected: () -> Unit,
  private val onSetRouteVolume: (SonosDevice, Int) -> Unit,
  private val rescanIntervalMs: Long = 5000L,
) : MediaRouteProvider(context) {

  private val devicesByRouteId = LinkedHashMap<String, SonosDevice>()
  private var scanJob: Job? = null

  override fun onDiscoveryRequestChanged(request: MediaRouteDiscoveryRequest?) {
    val wantsSonos =
      request != null && request.selector.hasControlCategory(CATEGORY_SONOS) && request.isActiveScan
    if (wantsSonos) startScanning() else stopScanning()
  }

  override fun onCreateRouteController(routeId: String): RouteController? {
    val device = devicesByRouteId[routeId] ?: return null
    return SonosRouteController(device)
  }

  private fun startScanning() {
    if (scanJob?.isActive == true) return
    scanJob =
      scope.launch {
        while (isActive) {
          val devices = withContext(Dispatchers.IO) { runCatching { discoverer.discover() }.getOrDefault(emptyList()) }
          publish(devices)
          delay(rescanIntervalMs)
        }
      }
  }

  private fun stopScanning() {
    scanJob?.cancel()
    scanJob = null
  }

  /** Replaces the published route set with [devices] (called on the provider's handler thread). */
  private fun publish(devices: List<SonosDevice>) {
    devicesByRouteId.clear()
    val builder = MediaRouteProviderDescriptor.Builder()
    for (device in devices) {
      devicesByRouteId[device.udn] = device
      val controlFilter = IntentFilter().apply { addCategory(CATEGORY_SONOS) }
      builder.addRoute(
        MediaRouteDescriptor.Builder(device.udn, device.name)
          .addControlFilter(controlFilter)
          .setDescription("Sonos")
          .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
          .setVolumeHandling(MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
          .setVolumeMax(MAX_VOLUME)
          .build()
      )
    }
    descriptor = builder.build()
    Timber.d("Sonos provider published %d route(s)", devices.size)
  }

  private inner class SonosRouteController(private val device: SonosDevice) : RouteController() {
    override fun onSelect() = onRouteSelected(device)

    override fun onUnselect(reason: Int) = onRouteUnselected()

    override fun onSetVolume(volume: Int) = onSetRouteVolume(device, volume.coerceIn(0, MAX_VOLUME))

    override fun onUpdateVolume(delta: Int) {
      // We don't cache per-route volume here; the player reads it back. Best-effort relative bump.
      onSetRouteVolume(device, delta.coerceIn(-MAX_VOLUME, MAX_VOLUME))
    }
  }

  companion object {
    /** Custom control category the Sonos routes advertise and the destination selector matches. */
    const val CATEGORY_SONOS = "com.audiobrowser.destination.sonos.CATEGORY_REMOTE_PLAYBACK"
    private const val MAX_VOLUME = 100
  }
}
