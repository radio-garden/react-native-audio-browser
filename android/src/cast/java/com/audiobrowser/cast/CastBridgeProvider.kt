package com.audiobrowser.cast

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.audiobrowser.Callbacks
import com.audiobrowser.player.Player
import com.google.android.gms.cast.framework.CastContext
import com.margelo.nitro.audiobrowser.CastState
import timber.log.Timber

/**
 * Cast-enabled variant of [CastBridgeProvider]. Same fully-qualified name as the `noCast` version;
 * exactly one compiles per build. Returns the real [CastBridge] backed by [CastSessionController].
 */
object CastBridgeProvider {
  fun create(context: Context): CastBridge = RealCastBridge(context.applicationContext)
}

/**
 * The real Cast bridge. Initialises the Cast context lazily on [configure] (so the OptionsProvider
 * reads the receiver id set by `configureCast()` first), then delegates everything to a
 * [CastSessionController]. The heavy `CastContext.getSharedInstance` first-touch is posted to the
 * main thread asynchronously so it never stalls the JS thread that called `configureCast()`.
 */
@UnstableApi
private class RealCastBridge(private val context: Context) : CastBridge {
  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile private var controller: CastSessionController? = null
  private var initStarted = false

  // Pending attach args / discovery leases captured before the controller exists (the session can
  // connect, and JS can retain discovery, before getSharedInstance finishes). Replayed once built.
  private var pendingSession: MediaSession? = null
  private var pendingPlayer: Player? = null
  private var pendingCallbacks: (() -> Callbacks?)? = null
  private var pendingLeases = 0

  override fun configure(receiverApplicationId: String?) {
    // Cheap + synchronous: stash the receiver id so the OptionsProvider sees it on first touch.
    CastConfigHolder.configure(receiverApplicationId)
    if (initStarted) return
    initStarted = true
    (context.applicationContext as? Application)?.let { CastActivityTracker.register(it) }
    // getSharedInstance must run on main AND does heavy first-call I/O — post it so the caller
    // (configureCast on the JS thread) returns immediately and the main thread isn't blocked.
    mainHandler.post {
      val ctrl =
        try {
          // First touch reflectively instantiates AudioBrowserCastOptionsProvider (manifest
          // meta-data), which reads CastConfigHolder. Requires Google Play services.
          val castContext = CastContext.getSharedInstance(context)
          CastSessionController(context, castContext)
        } catch (e: Exception) {
          Timber.e(e, "Failed to initialise Cast context (Play services unavailable?)")
          initStarted = false
          return@post
        }
      controller = ctrl
      pendingSession?.let { session ->
        pendingPlayer?.let { player -> ctrl.attach(session, player, pendingCallbacks ?: { null }) }
      }
      pendingSession = null
      pendingPlayer = null
      pendingCallbacks = null
      repeat(pendingLeases) { ctrl.retainDiscovery() }
      pendingLeases = 0
    }
  }

  override fun getState(): CastState = controller?.getState() ?: CastState.NO_DEVICES

  override fun getDeviceName(): String? = controller?.getDeviceName()

  override fun isCasting(): Boolean = controller?.isCasting() ?: false

  override fun showPicker() {
    controller?.showPicker()
  }

  override fun endSession() {
    controller?.endSession()
  }

  override fun attach(mediaSession: MediaSession, player: Player, callbacks: () -> Callbacks?) {
    val ctrl = controller
    if (ctrl != null) {
      ctrl.attach(mediaSession, player, callbacks)
    } else {
      // Stash until the controller is built (configure may still be initialising on main).
      pendingSession = mediaSession
      pendingPlayer = player
      pendingCallbacks = callbacks
    }
  }

  override fun retainDiscovery() {
    controller?.retainDiscovery() ?: run { pendingLeases++ }
  }

  override fun releaseDiscovery() {
    controller?.releaseDiscovery() ?: run { pendingLeases = (pendingLeases - 1).coerceAtLeast(0) }
  }

  override fun release() {
    controller?.release()
    controller = null
    initStarted = false
    pendingSession = null
    pendingPlayer = null
    pendingCallbacks = null
    pendingLeases = 0
  }
}
