import Foundation

#if canImport(NitroModules)
  import NitroModules
#endif

/// Transport surface the `PlaybackCoordinator` forwards to while a Cast session
/// owns playback (`isRemote == true`). Implemented by `CastSessionManager`.
///
/// Deliberately **free of any Google-Cast-SDK type** so it compiles in the
/// default (Cast-disabled) build: the local playback path keeps a
/// `weak var castTransport: CastTransportDelegate?` that is simply always nil
/// when Cast isn't built (nothing assigns it). Only the conforming
/// `CastSessionManager` lives behind `#if AUDIOBROWSER_ENABLE_CAST`.
@MainActor
protocol CastTransportDelegate: AnyObject {
  /// Resume playback on the Cast device.
  func castPlay()
  /// Pause playback on the Cast device.
  func castPause()
  /// Seek the Cast device to `seconds`.
  func castSeek(to seconds: Double)
  /// Make the receiver jump to the coordinator's current queue index (used after
  /// next/previous/skip mutate the local mirror).
  func castSkipToCurrentIndex()
  /// Stop playback on the Cast device (used when the local queue is cleared, to
  /// mirror the local unload — otherwise the receiver keeps playing). Stops the
  /// media only; the Cast session stays connected so a later queue mirrors back
  /// onto the same device.
  func castStop()
  /// Route the player volume (0…1) to the Cast device.
  func castSetVolume(_ level: Float)
  /// The Cast device's last-known playback position, in seconds.
  var castCurrentTime: Double { get }
  /// The current Cast media's duration, in seconds (0 for live / unknown).
  var castDuration: Double { get }
}

/// Snapshot of the receiver's playback state, surfaced from the remote client's
/// callbacks and fed back into the **same** `PlaybackStateMachine` so JS and the
/// now-playing surface treat Cast playback exactly like local playback.
/// Cast-SDK-free so it can cross the gating boundary.
struct CastRemoteState {
  /// The receiver's playback phase — the single Cast-SDK-free interpretation of
  /// the receiver player state. `loading` is kept distinct from `buffering`
  /// because the state machine treats both as "waiting", but only `buffering`
  /// (media already loaded) can accept a seek — see `Phase.allowsSeek`.
  enum Phase { case idle, loading, buffering, playing, paused }
  /// Why the receiver is idle (only meaningful when `phase == .idle`; `.none`
  /// otherwise). Cast-SDK-free mirror of `GCKMediaPlayerIdleReason` so this type
  /// crosses the gating boundary. Lets the coordinator distinguish end-of-queue
  /// (`.finished`) from an external interruption (`.interrupted`) and from
  /// transient/self-induced idle (`.cancelled`/`.none`).
  enum IdleReason { case none, finished, cancelled, interrupted, error }
  let phase: Phase
  let position: Double
  let duration: Double
  /// The receiver's current queue index, or nil if unknown. When this differs
  /// from the coordinator's `currentIndex`, the coordinator follows the receiver
  /// (the receiver auto-advanced its mirror, or external next/prev from a TV
  /// remote / Google Home moved it). Set by `CastSessionManager`.
  let currentIndex: Int?
  /// Why the receiver went idle. `.finished` on the LAST item drives end-of-queue
  /// handling (the mirrored queue played to its end).
  let idleReason: IdleReason
}

extension PlaybackCoordinator {
  // MARK: - Local suspension (Cast hand-off)

  /// Suspend the local AVPlayer when a Cast session takes over, without
  /// clearing the user's play intent (`playWhenReady` is preserved so the
  /// remote queueLoad autoplays and a later hand-back resumes locally).
  /// Idempotent. Call right after setting `castTransport`.
  func suspendLocalPlayback() {
    effectHandler?.pausePlayback()
    effectHandler?.cancelMediaLoading()
  }

  /// Resume LOCAL playback after a Cast session ends, picking up at the
  /// last-known remote position rather than restarting from 0. Reloads the
  /// current track (the live `src` re-resolves) with the position captured as a
  /// pending seek so the ready/play transition waits for it to land (no
  /// start-at-0 flash). `castTransport` must already be nil. For live streams
  /// (no seekable window) the position is ignored — they rejoin the live edge.
  func resumeLocalAfterCast(at position: Double, wasPlaying: Bool) {
    if position > 0, currentTrack?.live != true {
      loadSeekCoordinator.capture(position: position)
    }
    handleCurrentTrackChanged()
    if wasPlaying { play() }
  }

  // MARK: - Remote transport (called by HybridAudioBrowser / TrackPlayer seams)

  /// Forward a seek to the Cast device. Returns true if it was handled remotely
  /// (so the local seek is skipped).
  func castForwardSeek(to seconds: Double) -> Bool {
    guard isRemote else { return false }
    castTransport?.castSeek(to: seconds)
    return true
  }

  /// Forward a volume change to the Cast device. Returns true if handled remotely.
  func castForwardVolume(_ level: Float) -> Bool {
    guard isRemote else { return false }
    castTransport?.castSetVolume(level)
    return true
  }

  /// The Cast device's current position (seconds) while remote, else nil.
  var castPosition: Double? { isRemote ? castTransport?.castCurrentTime : nil }

  /// The Cast media's duration (seconds) while remote, else nil. 0 for live.
  var castDuration: Double? { isRemote ? castTransport?.castDuration : nil }

  // MARK: - Remote state intake

  /// Apply a receiver state snapshot into the shared state machine.
  ///
  /// Maps the remote phase to the same `PlaybackEvent`s the AVPlayer observers
  /// fire, so `nextPlaybackState` and every downstream callback / timer behave
  /// identically to local playback. Position/duration flow through the progress
  /// path via the effect handler's accessors on the local side; while remote,
  /// JS reads them from the snapshot the session manager caches.
  ///
  /// Crucially, this also **follows the receiver's queue index**: when the
  /// receiver auto-advances its mirror or an external surface (TV remote, Google
  /// Home) moves it, `remote.currentIndex` diverges from ours and we resync via
  /// `castSyncToRemoteIndex` — a remote-origin skip that updates Active Track /
  /// Now Playing / progress WITHOUT re-issuing a transport skip (no feedback).
  func applyRemoteState(_ remote: CastRemoteState) {
    guard isRemote else { return }

    // 1) Follow the receiver's queue position first (so the active-track-changed
    //    event lands before the state transition for the new item).
    if let remoteIndex = remote.currentIndex,
       remoteIndex >= 0, remoteIndex < tracks.count,
       remoteIndex != currentIndex {
      castSyncToRemoteIndex(remoteIndex)
    }

    // 2) End-of-queue: the receiver finished the last mirrored item. The
    //    repeat-aware decision is pure (CastRemoteAdvance); we apply its effect.
    switch CastRemoteAdvance.endOfQueueAction(
      idleReason: remote.idleReason,
      isLastInPlaybackOrder: isLastInPlaybackOrder,
      repeatMode: repeatMode,
    ) {
    case .repeatCurrent:
      castTransport?.castSkipToCurrentIndex()
      return
    case .advanceNext:
      next() // wraps; handleCurrentTrackChanged forwards the skip to the receiver
      return
    case .endNaturally:
      transition(.trackEndedNaturally)
      return
    case .none:
      break
    }

    // 3) Map the phase into the shared state machine.
    switch remote.phase {
    case .playing:
      transition(.avPlayerPlaying)
    case .buffering, .loading:
      transition(.avPlayerWaiting)
    case .paused:
      // Mirror a local pause without dropping play intent unexpectedly.
      transition(.avPlayerPaused(hasAsset: true))
    case .idle:
      switch remote.idleReason {
      case .interrupted:
        // The receiver was interrupted externally (another app/device cast over
        // us, or a hard stop) WITHOUT ending our session — reflect a stop rather
        // than freezing on the previous state with a stale position.
        transition(.avPlayerPaused(hasAsset: true))
      case .none, .finished, .cancelled, .error:
        // .finished → end-of-queue (handled in step 2); .error → surfaced via the
        // media-error → CastReSign path (so recoverable stale-URL errors aren't
        // misreported as terminal); .cancelled/.none are transient (our own
        // load/stop, or a momentary idle before media loads) — ignore.
        break
      }
    }
  }

  /// Surface a non-recoverable receiver error into the shared state machine — used
  /// when `CastReSign` can't recover a failed item (budget exhausted or no
  /// re-signable identity), so the player doesn't sit silently stuck on the
  /// receiver's idle/error. No-op when not casting.
  func applyRemoteError(_ error: TrackPlayerError.PlaybackError = .playbackFailed) {
    guard isRemote else { return }
    transition(.errorOccurred(error))
  }

  /// Follow the receiver to `index`: mutate the local queue index and emit the
  /// usual active-track-changed event, but suppress the outbound transport skip
  /// (the change originated from the receiver — re-issuing it would be feedback).
  private func castSyncToRemoteIndex(_ index: Int) {
    do {
      let result = try queue.skipTo(index)
      // remoteOrigin: this skip came from the receiver — don't echo it back.
      if case .trackChanged = result { handleCurrentTrackChanged(remoteOrigin: true) }
    } catch {
      logger.error("castSyncToRemoteIndex: skipTo(\(index)) failed: \(error.localizedDescription)")
    }
  }
}
