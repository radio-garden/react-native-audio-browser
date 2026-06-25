package com.audiobrowser.destination

import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouteSelector
import com.audiobrowser.Callbacks
import com.audiobrowser.cast.CastBridge
import com.audiobrowser.destination.sonos.SonosBackend
import com.audiobrowser.player.Player
import com.margelo.nitro.audiobrowser.CastState
import timber.log.Timber

/**
 * Fronts the two playback-destination backends — Google Cast ([CastBridge], Cast-sourceset-gated)
 * and Sonos ([SonosBackend], always present) — behind the single cross-backend destination surface
 * the JS API exposes (`getCastState`, `isCasting`, `showCastPicker`, discovery leases, …). It
 * multiplexes their state into one value, drives discovery on both, and presents ONE MediaRouter
 * chooser whose selector is the union of both backends' route categories (so Cast and Sonos devices
 * appear together). Only one backend is ever connected at a time — MediaRouter enforces a single
 * selected route across providers.
 */
@UnstableApi
class DestinationCoordinator(
  private val castBridge: CastBridge,
  private val sonosBackend: SonosBackend,
) {
  private val mainHandler = Handler(Looper.getMainLooper())

  fun configureCast(receiverApplicationId: String?) = castBridge.configure(receiverApplicationId)

  fun attach(mediaSession: MediaSession, player: Player, callbacks: () -> Callbacks?) {
    castBridge.attach(mediaSession, player, callbacks)
    sonosBackend.attach(mediaSession, player, callbacks)
  }

  /** Combined state: the more-connected of the two backends wins. */
  fun getState(): CastState = higherOf(castBridge.getState(), sonosBackend.getState())

  fun getDeviceName(): String? =
    if (sonosBackend.isCasting()) sonosBackend.getDeviceName() else castBridge.getDeviceName()

  fun isCasting(): Boolean = castBridge.isCasting() || sonosBackend.isCasting()

  fun retainDiscovery() {
    castBridge.retainDiscovery()
    sonosBackend.retainDiscovery()
  }

  fun releaseDiscovery() {
    castBridge.releaseDiscovery()
    sonosBackend.releaseDiscovery()
  }

  fun endSession() {
    castBridge.endSession()
    sonosBackend.endSession()
  }

  /** Presents one chooser listing both Cast and Sonos routes. */
  fun showPicker() {
    mainHandler.post {
      val activity = DestinationActivityTracker.current
      if (activity == null) {
        Timber.w("showCastPicker: no foreground Activity to present the chooser; skipping")
        return@post
      }
      val builder = MediaRouteSelector.Builder().addSelector(sonosBackend.routeSelector)
      castBridge.routeSelector()?.let { builder.addSelector(it) }
      MediaRouteChooserDialog(activity).apply {
        routeSelector = builder.build()
        show()
      }
    }
  }

  fun release() {
    castBridge.release()
    sonosBackend.release()
  }

  /** CONNECTED > CONNECTING > NOT_CONNECTED > NO_DEVICES. */
  private fun higherOf(a: CastState, b: CastState): CastState =
    if (rank(a) >= rank(b)) a else b

  private fun rank(state: CastState): Int =
    when (state) {
      CastState.CONNECTED -> 3
      CastState.CONNECTING -> 2
      CastState.NOT_CONNECTED -> 1
      CastState.NO_DEVICES -> 0
    }
}
