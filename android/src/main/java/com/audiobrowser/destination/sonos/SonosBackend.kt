package com.audiobrowser.destination.sonos

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.audiobrowser.Callbacks
import com.audiobrowser.browser.resolveMediaUrl
import com.audiobrowser.cast.CastDiscoveryLeases
import com.audiobrowser.cast.CastStateResolver
import com.audiobrowser.player.InterceptingPlayer
import com.audiobrowser.player.Player
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.CastState
import com.margelo.nitro.audiobrowser.CastStateChangedEvent
import com.margelo.nitro.audiobrowser.MediaResolveTarget
import com.margelo.nitro.audiobrowser.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * The Sonos playback-destination backend (main sourceset; no Cast SDK). Owns the
 * [SonosMediaRouteProvider] and turns MediaRouter route selection into the same `Player` swap the
 * Cast backend uses: on select it resolves the Active Track's media URL with `target:'cast'` (a
 * self-contained URL the speaker fetches itself), builds a [SonosPlayer], and calls
 * `player.startCasting`; on unselect it calls `player.stopCasting`. Discovery is ref-counted to JS
 * leases exactly like Cast (a MediaRouter active scan drives the provider's SSDP probe).
 *
 * Emits the existing [CastStateChangedEvent] so the cross-backend destination state in JS reflects
 * Sonos with no new API. All state is confined to the main thread.
 */
@UnstableApi
class SonosBackend(
  private val context: Context,
  private val httpClient: OkHttpClient = OkHttpClient(),
) {
  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private val scope = MainScope()

  private var mediaSession: MediaSession? = null
  private var player: Player? = null
  private var callbacksProvider: () -> Callbacks? = { null }

  private var currentDevice: SonosDevice? = null
  @Volatile private var connecting = false
  private var connectJob: Job? = null

  private val soapClient = SoapClient(httpClient)

  private val multicastLock: MulticastLockHandle? by lazy {
    val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val lock = wifi?.createMulticastLock("audiobrowser-sonos-ssdp")?.apply { setReferenceCounted(true) }
    lock?.let {
      object : MulticastLockHandle {
        override fun acquire() = it.acquire()

        override fun release() {
          if (it.isHeld) it.release()
        }
      }
    }
  }

  private val discoverer by lazy { SonosDiscoverer(httpClient, SsdpDiscovery(multicastLock)) }

  private val provider by lazy {
    SonosMediaRouteProvider(
      context = appContext,
      discoverer = discoverer,
      scope = scope,
      onRouteSelected = ::onRouteSelected,
      onRouteUnselected = ::onRouteUnselected,
      onSetRouteVolume = ::onSetRouteVolume,
    )
  }

  private val mediaRouter: MediaRouter by lazy { MediaRouter.getInstance(appContext) }

  /** The selector that matches Sonos routes; unioned with the Cast selector for the picker. */
  val routeSelector: MediaRouteSelector by lazy {
    MediaRouteSelector.Builder().addControlCategory(SonosMediaRouteProvider.CATEGORY_SONOS).build()
  }

  private val leases = CastDiscoveryLeases()
  private var scanning = false
  private var providerAdded = false
  private val routerCallback =
    object : MediaRouter.Callback() {
      override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = emitCurrentState()

      override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = emitCurrentState()

      override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = emitCurrentState()
    }

  fun attach(mediaSession: MediaSession, player: Player, callbacks: () -> Callbacks?) {
    runOnMain {
      this.mediaSession = mediaSession
      this.player = player
      this.callbacksProvider = callbacks
      if (!providerAdded) {
        mediaRouter.addProvider(provider)
        providerAdded = true
      }
    }
  }

  fun getState(): CastState =
    CastStateResolver.resolve(
      connected = currentDevice != null,
      connecting = connecting,
      hasDevices = hasAvailableDevices(),
    )

  fun getDeviceName(): String? = currentDevice?.name

  fun isCasting(): Boolean = currentDevice != null

  fun endSession() {
    runOnMain { mediaRouter.unselect(MediaRouter.UNSELECT_REASON_DISCONNECTED) }
  }

  fun retainDiscovery() {
    runOnMain {
      if (leases.retain() && !scanning) {
        scanning = true
        mediaRouter.addCallback(routeSelector, routerCallback, ACTIVE_SCAN_FLAGS)
        emitCurrentState()
      }
    }
  }

  fun releaseDiscovery() {
    runOnMain {
      if (leases.release() && scanning) {
        scanning = false
        mediaRouter.removeCallback(routerCallback)
      }
    }
  }

  fun release() {
    runOnMain {
      connecting = false
      connectJob?.cancel()
      connectJob = null
      if (scanning) {
        mediaRouter.removeCallback(routerCallback)
        scanning = false
      }
      leases.reset()
      if (providerAdded) {
        mediaRouter.removeProvider(provider)
        providerAdded = false
      }
      currentDevice = null
    }
    scope.cancel()
  }

  // MARK: - Route selection -> Player swap

  private fun onRouteSelected(device: SonosDevice) {
    runOnMain {
      val player = player ?: return@runOnMain
      if (player.castPlayer != null || connecting) return@runOnMain
      connecting = true
      currentDevice = device
      emitState(CastState.CONNECTING)
      connectJob =
        scope.launch {
          try {
            val snapshot = player.captureQueueState()
            val active = snapshot.tracks.getOrNull(snapshot.startIndex) ?: snapshot.tracks.firstOrNull()
            if (active == null) {
              connecting = false
              currentDevice = null
              emitCurrentState()
              return@launch
            }
            val item = buildRemoteMediaItem(player, active)
            val transport = SonosTransport(device, soapClient)
            val sonosPlayer =
              SonosPlayer(
                looper = Looper.getMainLooper(),
                device = device,
                transport = transport,
                scope = scope,
                initialMediaItem = item,
                onFatalError = { Timber.e(it, "Sonos playback error") },
              )
            val intercepting =
              InterceptingPlayer(
                sonosPlayer,
                callbacksProvider,
                { player.getOptions() },
                keepSessionAliveOnError = false,
              )
            player.startCasting(sonosPlayer, intercepting, listOf(item), snapshot)
            connecting = false
            connectJob = null
            emitState(CastState.CONNECTED)
          } catch (t: Throwable) {
            Timber.e(t, "Sonos connect failed")
            connecting = false
            currentDevice = null
            connectJob = null
            emitCurrentState()
          }
        }
    }
  }

  private fun onRouteUnselected() {
    runOnMain {
      connecting = false
      connectJob?.cancel()
      connectJob = null
      val player = player
      if (player?.castPlayer != null) player.stopCasting()
      currentDevice = null
      emitCurrentState()
    }
  }

  private fun onSetRouteVolume(device: SonosDevice, volume: Int) {
    val player = player ?: return
    runOnMain { player.activePlayer.setDeviceVolume(volume.coerceIn(0, 100), 0) }
  }

  /** Resolves the Active Track's media URL with target:'cast' and builds a Media3 MediaItem. */
  private suspend fun buildRemoteMediaItem(player: Player, track: Track): MediaItem {
    val browserManager = player.browser?.browserManager
    val mediaUri =
      track.src?.let { src ->
        runCatching { browserManager?.resolveMediaUrl(src, MediaResolveTarget.CAST) }.getOrNull()?.path
          ?: src
      } ?: ""
    val base = TrackFactory.toMedia3(track)
    return base.buildUpon().setUri(Uri.parse(mediaUri)).setTag(track).build()
  }

  // MARK: - Helpers

  private fun hasAvailableDevices(): Boolean =
    mediaRouter.routes.any { it.matchesSelector(routeSelector) }

  private fun emitCurrentState() = emitState(getState())

  private fun emitState(state: CastState) {
    callbacksProvider()?.onCastStateChanged(CastStateChangedEvent(state, getDeviceName()))
  }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
  }

  private companion object {
    const val ACTIVE_SCAN_FLAGS = MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
  }
}
