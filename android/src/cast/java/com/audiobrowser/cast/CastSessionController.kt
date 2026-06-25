package com.audiobrowser.cast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.cast.CastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.audiobrowser.Callbacks
import com.audiobrowser.destination.RemoteTrackResolver
import com.audiobrowser.player.InterceptingPlayer
import com.audiobrowser.player.Player
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.margelo.nitro.audiobrowser.CastState
import com.margelo.nitro.audiobrowser.CastStateChangedEvent
import com.margelo.nitro.audiobrowser.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The heart of the Android Cast subsystem (see ADR 0003). Owns the [SessionManagerListener]: on
 * session start/resume it builds a Media3 [CastPlayer], transfers the queue + position from the
 * active local player, and repoints the [MediaSession] at an `InterceptingPlayer(castPlayer)`; on
 * session end it transfers state back to the local player. It emits [CastStateChangedEvent] to JS
 * via [Callbacks], and owns discovery ref-counting (a [MediaRouter] active scan that runs only
 * while JS holds at least one discovery lease via `retainCastDiscovery()`).
 *
 * All Cast-SDK access is confined to this sourceset; the core only ever sees the [CastBridge].
 */
@UnstableApi
class CastSessionController(
  private val context: Context,
  private val castContext: CastContext,
) {
  private val mainHandler = Handler(Looper.getMainLooper())

  // Main-confined scope for the queue-resolution coroutine and CastReSign work. CastPlayer and the
  // session swap must touch main; resolution suspends onto IO inside the browser pipeline.
  private val scope = MainScope()

  private var mediaSession: MediaSession? = null
  private var player: Player? = null
  private var callbacksProvider: () -> Callbacks? = { null }

  private var castPlayer: CastPlayer? = null
  private var reSign: CastReSign? = null
  private var remoteMediaClient: RemoteMediaClient? = null
  private var remoteMediaCallback: RemoteMediaClient.Callback? = null

  // Connect handoff is a suspending coroutine (it builds the queue's cast URLs before
  // player.startCasting flips player.castPlayer). [connecting] is set synchronously BEFORE the
  // launch so a fast connect→disconnect (or attach-after-connect) sees a connect in progress while
  // player.castPlayer is still null, and [connectJob] lets disconnect/release cancel the in-flight
  // build before it repoints the session at a dead CastPlayer. Both touched on main only.
  @Volatile private var connecting = false
  private var connectJob: Job? = null

  // Discovery: an active MediaRouter scan is expensive, so it's native-ref-counted to JS discovery
  // leases (retainCastDiscovery / releaseCastDiscovery, driven by mounted useCastState() hooks).
  private val mediaRouter: MediaRouter by lazy { MediaRouter.getInstance(context.applicationContext) }
  // Pure lease counter (testable, in main); this controller turns its 0↔1 edges into MediaRouter
  // start/stop scan calls. [scanning] tracks whether the scan is currently active (a showPicker()
  // call can also start it transiently, independent of leases).
  private val discoveryLeases = CastDiscoveryLeases()
  private var scanning = false
  private val routerCallback =
    object : MediaRouter.Callback() {
      override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) =
        emitCurrentState()

      override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) =
        emitCurrentState()

      override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) =
        emitCurrentState()
    }

  private val routeSelector: MediaRouteSelector by lazy {
    val receiverAppId =
      CastConfigHolder.receiverApplicationId
        ?: CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
    MediaRouteSelector.Builder()
      .addControlCategory(CastMediaControlIntent.categoryForCast(receiverAppId))
      .build()
  }

  private val sessionListener =
    object : SessionManagerListener<CastSession> {
      override fun onSessionStarted(session: CastSession, sessionId: String) = onSessionConnected()

      override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) =
        onSessionConnected()

      override fun onSessionStarting(session: CastSession) = emitState(CastState.CONNECTING)

      override fun onSessionResuming(session: CastSession, sessionId: String) =
        emitState(CastState.CONNECTING)

      override fun onSessionEnded(session: CastSession, error: Int) = onSessionDisconnected()

      override fun onSessionSuspended(session: CastSession, reason: Int) = onSessionDisconnected()

      override fun onSessionStartFailed(session: CastSession, error: Int) {
        Timber.w("Cast session start failed: $error")
        onSessionDisconnected()
      }

      override fun onSessionResumeFailed(session: CastSession, error: Int) {
        Timber.w("Cast session resume failed: $error")
        onSessionDisconnected()
      }

      override fun onSessionEnding(session: CastSession) {}
    }

  init {
    castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
  }

  /**
   * Hands the controller the live session + local player (idempotent). Marshals onto main like
   * every other public entry point: `attach` is reachable from `AudioBrowser.configureCast` (a Nitro
   * method, not guaranteed main) as well as `onServiceConnected` (main), and all controller state +
   * the Cast SDK session-listener callbacks are main-confined. `runOnMain` runs inline when already
   * on main (so the bridge's pending-replay, which posts `attach` then `retainDiscovery` from the
   * same main-thread block, stays correctly ordered — attach completes before the leases replay)
   * and posts otherwise; the bridge itself never posts `attach`, so there is no double-marshal.
   */
  fun attach(mediaSession: MediaSession, player: Player, callbacks: () -> Callbacks?) {
    runOnMain {
      if (this.player === player && this.mediaSession === mediaSession) return@runOnMain
      this.mediaSession = mediaSession
      this.player = player
      this.callbacksProvider = callbacks
      // If a session was already live when we attached (e.g. configureCast after connect), adopt it.
      if (castContext.sessionManager.currentCastSession?.isConnected == true) {
        onSessionConnected()
      }
    }
  }

  // MARK: - State

  fun getState(): CastState {
    val session = castContext.sessionManager.currentCastSession
    return CastStateResolver.resolve(
      connected = session?.isConnected == true,
      connecting = session?.isConnecting == true,
      hasDevices = hasAvailableDevices(),
    )
  }

  fun getDeviceName(): String? =
    castContext.sessionManager.currentCastSession?.castDevice?.friendlyName

  fun isCasting(): Boolean = castContext.sessionManager.currentCastSession?.isConnected == true

  /**
   * The Cast route selector, exposed so the DestinationCoordinator can union it with Sonos's and
   * present one chooser (the coordinator owns picker presentation, not this controller).
   */
  fun currentRouteSelector(): MediaRouteSelector = routeSelector

  fun endSession() {
    runOnMain { castContext.sessionManager.endCurrentSession(true) }
  }

  // MARK: - Discovery ref-count (native, driven by JS retain/release leases)

  fun retainDiscovery() {
    runOnMain {
      // 0→1 edge: start the active scan (unless showPicker() already started it).
      if (discoveryLeases.retain() && !scanning) {
        scanning = true
        mediaRouter.addCallback(routeSelector, routerCallback, ACTIVE_SCAN_FLAGS)
        emitCurrentState()
      }
    }
  }

  fun releaseDiscovery() {
    runOnMain {
      // 1→0 edge: stop the active scan.
      if (discoveryLeases.release() && scanning) {
        scanning = false
        mediaRouter.removeCallback(routerCallback)
      }
    }
  }

  // MARK: - Session lifecycle

  private fun onSessionConnected() {
    val player = player ?: return
    // Already casting OR a connect is already in flight — don't build a second CastPlayer.
    if (player.castPlayer != null || connecting) return
    connecting = true
    emitState(CastState.CONNECTING)

    // Resolve the full queue's media (+ artwork) URLs with target:'cast' off the main thread, then
    // hand the built Cast MediaItems back to the player on main to perform the swap. Receiver URLs
    // must be self-contained (query-signed) because the Cast device fetches them itself.
    connectJob =
      scope.launch {
        val snapshot = player.captureQueueState()
        val castMediaItems = snapshot.tracks.map { track -> buildCastMediaItem(player, track) }

        // The build suspended; the session may have gone away (fast connect→disconnect) or a
        // disconnect may have cancelled us. Bail before touching the player/session so we never
        // repoint at a dead CastPlayer or strand isLocal=false. (Cancellation is also caught by the
        // job; this is the belt for the not-cancelled-but-disconnected case.)
        if (!connecting || castContext.sessionManager.currentCastSession?.isConnected != true) {
          connecting = false
          emitCurrentState()
          return@launch
        }

        val cast = CastPlayer(castContext, CastMediaItemConverter())
        castPlayer = cast
        reSign = CastReSign(browserManager = { player.browser?.browserManager }, scope = scope)

        val intercepting =
          InterceptingPlayer(
            cast,
            callbacksProvider,
            { player.getOptions() },
            keepSessionAliveOnError = false,
          )
        // startCasting returns false if another destination (Sonos) won the swap concurrently —
        // then we are NOT casting: release the CastPlayer we built and don't report CONNECTED.
        if (!player.startCasting(cast, intercepting, castMediaItems, snapshot)) {
          cast.release()
          castPlayer = null
          reSign = null
          connecting = false
          connectJob = null
          emitCurrentState()
          return@launch
        }
        reSign?.reset()

        // Reactive re-sign: when the receiver lands in IDLE with a load error (a likely stale signed
        // URL on a multi-hour live session), JIT re-resolve that one item and update it. Bounded by
        // CastReSign so a genuinely dead stream doesn't loop forever. LIVE streams
        // may also transiently idle; the per-item attempt cap is the backstop against a reload loop.
        val rmc = castContext.sessionManager.currentCastSession?.remoteMediaClient
        remoteMediaClient = rmc
        if (rmc != null) {
          val cb =
            object : RemoteMediaClient.Callback() {
              override fun onStatusUpdated() {
                val status = rmc.mediaStatus ?: return
                val idleWithError =
                  status.playerState == MediaStatus.PLAYER_STATE_IDLE &&
                    status.idleReason == MediaStatus.IDLE_REASON_ERROR
                if (idleWithError) {
                  reSign?.onLoadError(rmc)
                }
              }
            }
          remoteMediaCallback = cb
          rmc.registerCallback(cb)
        }

        connecting = false
        connectJob = null
        emitState(CastState.CONNECTED)
      }
  }

  /**
   * Builds one Cast-queue MediaItem for [track]: media + artwork resolved with target:'cast' (a
   * self-contained, receiver-fetchable URL) via the shared [RemoteTrackResolver], then mapped to a
   * Cast queue item.
   */
  private suspend fun buildCastMediaItem(player: Player, track: Track): MediaItem {
    val resolved = RemoteTrackResolver.resolve(player, track)
    return CastMediaItemConverter.mediaItemFor(resolved.track, Uri.parse(resolved.mediaUri))
  }

  private fun onSessionDisconnected() {
    runOnMain {
      // Cancel any in-flight connect handoff and clear the flag FIRST, so a coroutine that already
      // passed its post-suspension session re-check can't repoint the session after we tear down,
      // and a coroutine still suspended bails on resume.
      connecting = false
      connectJob?.cancel()
      connectJob = null

      val player = player
      if (player?.castPlayer != null) {
        player.stopCasting()
      }
      remoteMediaCallback?.let { cb -> remoteMediaClient?.unregisterCallback(cb) }
      remoteMediaCallback = null
      remoteMediaClient = null
      // Release any controller-held CastPlayer even if startCasting hadn't flipped player.castPlayer
      // yet (teardown raced ahead of the connect coroutine).
      castPlayer?.release()
      castPlayer = null
      reSign = null
      emitCurrentState()
    }
  }

  // MARK: - Helpers

  private fun hasAvailableDevices(): Boolean =
    mediaRouter.routes.any { it.matchesSelector(routeSelector) && !it.isDefaultOrBluetooth }

  private fun emitCurrentState() = emitState(getState())

  private fun emitState(state: CastState) {
    val event = CastStateChangedEvent(state, getDeviceName())
    callbacksProvider()?.onCastStateChanged(event)
  }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
  }

  /** Full teardown — called from the bridge on AudioBrowser.dispose() (e.g. a JS runtime reload). */
  fun release() {
    castContext.sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)
    runOnMain {
      connecting = false
      connectJob?.cancel()
      connectJob = null
      remoteMediaCallback?.let { cb -> remoteMediaClient?.unregisterCallback(cb) }
      remoteMediaCallback = null
      remoteMediaClient = null
      if (scanning) {
        mediaRouter.removeCallback(routerCallback)
        scanning = false
      }
      discoveryLeases.reset()
      castPlayer?.release()
      castPlayer = null
      reSign = null
    }
    scope.cancel()
  }

  companion object {
    private const val ACTIVE_SCAN_FLAGS = MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
  }
}
