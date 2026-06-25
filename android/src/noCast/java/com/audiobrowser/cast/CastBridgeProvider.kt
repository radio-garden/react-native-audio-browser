package com.audiobrowser.cast

import android.content.Context
import androidx.media3.session.MediaSession
import androidx.mediarouter.media.MediaRouteSelector
import com.audiobrowser.Callbacks
import com.audiobrowser.player.Player
import com.margelo.nitro.audiobrowser.CastState

/**
 * Default-build (Cast-disabled) variant of [CastBridgeProvider]. Defined with the SAME
 * fully-qualified name as the `cast`-sourceset version; exactly one compiles per build. This one
 * returns an inert [CastBridge] that never links any Cast-SDK class, keeping the default build
 * byte-for-byte behaviorally unchanged.
 */
object CastBridgeProvider {
  fun create(@Suppress("UNUSED_PARAMETER") context: Context): CastBridge = NoopCastBridge
}

/** The inert Cast bridge: state is always [CastState.NO_DEVICES] and every method does nothing. */
private object NoopCastBridge : CastBridge {
  override fun configure(receiverApplicationId: String?) {}

  override fun getState(): CastState = CastState.NO_DEVICES

  override fun getDeviceName(): String? = null

  override fun isCasting(): Boolean = false

  override fun showPicker() {}

  override fun routeSelector(): MediaRouteSelector? = null

  override fun endSession() {}

  override fun attach(mediaSession: MediaSession, player: Player, callbacks: () -> Callbacks?) {}

  override fun retainDiscovery() {}

  override fun releaseDiscovery() {}

  override fun release() {}
}
