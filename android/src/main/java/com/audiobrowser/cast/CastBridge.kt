package com.audiobrowser.cast

import androidx.media3.session.MediaSession
import com.audiobrowser.Callbacks
import com.audiobrowser.player.Player
import com.margelo.nitro.audiobrowser.CastState

/**
 * The seam between the always-compiled core (`AudioBrowser`, `Player`, `Service`) and the optional
 * Google Cast subsystem. It is defined in the **main** sourceset so the core can reference it
 * unconditionally, but its only concrete implementations live in the variant sourcesets:
 * - `noCast` — an inert [CastBridge] whose state is always [CastState.NO_DEVICES] and whose methods
 *   do nothing (the default build never links the Cast SDK).
 * - `cast` — the real implementation backed by Media3 `CastPlayer` + `play-services-cast-framework`.
 *
 * Exactly one of those variants compiles per build. The core obtains its bridge through
 * [CastBridgeProvider.create], which is defined with the SAME fully-qualified name in BOTH variant
 * sourcesets — so the call site in `AudioBrowser` resolves to whichever variant is active without
 * referencing any Cast-SDK type from `main`.
 *
 * See ADR 0003 (`docs/adr/0003-google-cast-is-a-mirrored-playback-destination.md`) and
 * `CONTEXT.md` → "Playback destinations" for the domain model.
 */
interface CastBridge {

  /**
   * Idempotently initialises the Cast SDK and discovery wiring. The receiver application id is
   * stashed in a process-static holder *before* the Cast context is first created; a null id
   * resolves to Google's Default Media Receiver. No-op on the inert (`noCast`) bridge.
   */
  fun configure(receiverApplicationId: String?)

  /** Current Cast connection lifecycle. [CastState.NO_DEVICES] on a non-Cast build / pre-configure. */
  fun getState(): CastState

  /** The connected Cast device's friendly name, or null when not connected. */
  fun getDeviceName(): String?

  /** True while a Cast session is connected (audio is on the Cast device). */
  fun isCasting(): Boolean

  /** Presents the system Cast chooser. No-op if Cast is not configured. */
  fun showPicker()

  /** Disconnects the current Cast session, handing playback back to the local player. */
  fun endSession()

  /**
   * Hands the bridge the live [MediaSession] and the local [Player] so it can repoint the session's
   * player at a `CastPlayer` on connect and back at the local `InterceptingPlayer` on disconnect.
   * Called from `AudioBrowser.onServiceConnected` (and again from `configureCast` if the service is
   * already up) once the session is built; the bridge/controller attaches once and ignores a
   * re-attach. No-op on the inert bridge.
   */
  fun attach(mediaSession: MediaSession, player: Player, callbacks: () -> Callbacks?)

  /**
   * Discovery ref-count, driven by JS discovery leases (`retainCastDiscovery()` /
   * `releaseCastDiscovery()`, called from mounted `useCastState()` hooks). Active device scanning is
   * expensive, so it only runs while leases > 0. The inert bridge ignores both.
   */
  fun retainDiscovery()

  fun releaseDiscovery()

  /**
   * Full teardown of the Cast subsystem: tears down the session listener, discovery scan, remote
   * callback, and CastPlayer. Called from `AudioBrowser.dispose()` (e.g. a JS runtime reload) so a
   * new instance doesn't double-register against the process-static Cast context. No-op on the
   * inert bridge.
   */
  fun release()
}
