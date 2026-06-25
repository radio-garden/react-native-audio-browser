import Foundation
import os.log

#if AUDIOBROWSER_ENABLE_CAST

  import GoogleCast

  /// Owns the Google Cast session lifecycle on iOS and bridges it to the
  /// `PlaybackCoordinator`.
  ///
  /// ADR-0003 (iOS): there is no swappable player object. While a Cast session
  /// is connected, this manager makes the coordinator **suspend the local
  /// AVPlayer** (by setting `coordinator.castTransport = self`, which flips
  /// `coordinator.isRemote`) and routes transport + queue-load to
  /// `GCKRemoteMediaClient`. Position/state come back from the remote client's
  /// callbacks and are pushed into the **same** `PlaybackStateMachine` via
  /// `coordinator.applyRemoteState`.
  ///
  /// Discovery is library-driven and **native-ref-counted to mounted
  /// `useCastState` hooks** via `retainDiscovery()` / `releaseDiscovery()`, which
  /// JS drives from the spec's `retainCastDiscovery()` / `releaseCastDiscovery()`
  /// methods (ADR-0003: no native `<CastButton/>`; discovery is tied to live
  /// consumers, not a view lifecycle). It is NOT tied to the `onCastStateChanged`
  /// property — `NativeUpdatedValue.emitterize` installs that callback once at
  /// module load and never signals unsubscribe, so it can't drive a ref-count.
  ///
  /// ## Required app Info.plist keys (cannot be hidden by the library)
  /// - `NSLocalNetworkUsageDescription` — copy explaining why local-network
  ///   access is needed.
  /// - `NSBonjourServices` — must include `_googlecast._tcp` and
  ///   `_<receiverAppId>._googlecast._tcp`.
  /// - iOS 14+ shows the local-network permission prompt on first discovery.
  @MainActor
  final class CastSessionManager: NSObject {
    private let logger = Logger(subsystem: "com.audiobrowser", category: "CastSessionManager")

    /// Reaches back into playback core. Held weakly — the coordinator outlives
    /// the manager via TrackPlayer; the manager must not retain it.
    private weak var coordinator: PlaybackCoordinator?

    /// Emits a Cast state change to JS as a single event object. Set by
    /// HybridAudioBrowser.
    var onStateChanged: ((CastStateChangedEvent) -> Void)?

    private let reSign = CastReSign()

    /// Owns building + maintaining the receiver's mirrored queue (current-item-
    /// first load, insert-around-current, rehydration). Extracted so this object
    /// stays focused on session lifecycle + delegate wiring.
    private let queueMirror: CastQueueMirror

    /// Discovery subscriber ref-count (live `onCastStateChanged` / `useCastState`
    /// consumers). Discovery runs while > 0.
    private var discoverySubscribers = 0

    /// Whether `configureCast` has run and the GCKCastContext is initialised.
    private(set) var isConfigured = false

    /// The receiver app id this manager was configured with (for detecting an
    /// ignored runtime change).
    private var configuredReceiverAppId: String?

    /// `GCKCastContext.setSharedInstanceWith` may be called only ONCE per
    /// process. A JS runtime reload creates a fresh HybridAudioBrowser (and a
    /// fresh manager); on the second configure we must reuse the existing shared
    /// context rather than re-initialise it (which traps). Process-static.
    private nonisolated(unsafe) static var sharedContextInitialised = false

    /// Cached last-known remote position so JS `getProgress()` has a value while
    /// casting (the local AVPlayer is suspended).
    private(set) var remotePosition: Double = 0
    private(set) var remoteDuration: Double = 0

    /// A seek requested before the receiver finished loading its media. The Cast
    /// SDK silently drops a seek issued with no media loaded (e.g. during the
    /// queue-load window right after connecting), so we hold the latest target and
    /// apply it once the receiver reports a ready (non-idle/non-loading) state.
    private var pendingSeek: Double?

    /// Observer token for `GCKCastContext.castState` changes (device discovery +
    /// connection lifecycle). These are NOT `GCKSessionManagerListener` events, so
    /// without this the `noDevices ↔ notConnected ↔ connecting` edges would never
    /// reach JS — only the connected/disconnected session edges would.
    private var castStateObserver: NSObjectProtocol?

    /// Coalesces duplicate state emits (the session-lifecycle callbacks and the
    /// discovery observer can both fire for a single transition; without this JS
    /// could see a `.connected → .connecting → .connected` flicker as the lagging
    /// context observer catches up). Pure + testable.
    private var coalescer = CastStateCoalescer()

    init(
      coordinator: PlaybackCoordinator,
      resolveMediaUrl: @escaping (_ src: String, _ track: Track) async -> String,
      resolveArtworkUrl: @escaping (_ track: Track) async -> String?,
    ) {
      self.coordinator = coordinator
      // The mirror is handed its receiver-client accessor rather than reaching into
      // the GCK singleton itself (it only runs during an active session, so this is
      // always resolved by then).
      queueMirror = CastQueueMirror(
        coordinator: coordinator,
        remoteClient: { GCKCastContext.sharedInstance().sessionManager.currentCastSession?.remoteMediaClient },
        resolveMediaUrl: resolveMediaUrl,
        resolveArtworkUrl: resolveArtworkUrl,
      )
      super.init()
      reSign.resolveCastUrl = resolveMediaUrl
    }

    deinit {
      // `tearDown()` removes the discovery observer on the JS-reload path, but a
      // dealloc that doesn't route through it (e.g. the weak `shared` is already
      // gone) would otherwise leak the block-based observer into the
      // NotificationCenter singleton for the life of the process. `removeObserver`
      // is thread-safe and this is the final reference, so removing here is safe.
      if let castStateObserver {
        NotificationCenter.default.removeObserver(castStateObserver)
      }
    }

    // MARK: - Configuration

    /// Idempotently initialise the Cast context. Must set the receiver app id in
    /// the options BEFORE `GCKCastContext.setSharedInstanceWith` is first called.
    func configure(receiverApplicationId: String?) {
      let appId = receiverApplicationId ?? kGCKDefaultMediaReceiverApplicationID
      guard !isConfigured else {
        // `setSharedInstanceWith` is process-once, so a second configure with a
        // DIFFERENT receiver app id cannot take effect at runtime — warn loudly
        // rather than silently ignore (a changed id needs a process restart).
        if appId != configuredReceiverAppId {
          logger.error(
            "configureCast: ignoring changed receiver app id (\(self.configuredReceiverAppId ?? "nil", privacy: .public) → \(appId, privacy: .public)); the Cast context is initialised once per process and cannot be re-pointed at runtime — restart the app to change it",
          )
        } else {
          logger.info("configureCast: already configured with the same app id — ignoring")
        }
        return
      }
      // Initialise the process-shared context at most once. On a JS reload the
      // shared instance already exists; reuse it (re-init traps).
      if !Self.sharedContextInitialised {
        let criteria = GCKDiscoveryCriteria(applicationID: appId)
        let options = GCKCastOptions(discoveryCriteria: criteria)
        // We drive discovery ourselves (ref-counted), so don't start it eagerly.
        options.startDiscoveryAfterFirstTapOnCastButton = false
        // Keep the session alive while the app is backgrounded — audio is playing
        // ON THE CAST DEVICE and must continue when the phone is in the user's
        // pocket. Suspending it would tear down the very session we want to keep.
        options.suspendSessionsWhenBackgrounded = false
        GCKCastContext.setSharedInstanceWith(options)
        Self.sharedContextInitialised = true
      }

      GCKCastContext.sharedInstance().sessionManager.add(self)
      isConfigured = true
      configuredReceiverAppId = appId
      logger.info("configureCast: initialised GCKCastContext (appId=\(appId, privacy: .public))")

      // Observe discovery/connection-state changes (a device appears on the
      // network, the user taps a device in the picker) so those transitions reach
      // JS too. GCK posts this on the main thread; hop to the main actor to emit.
      if castStateObserver == nil {
        castStateObserver = NotificationCenter.default.addObserver(
          forName: NSNotification.Name(kGCKCastStateDidChangeNotification),
          object: nil,
          queue: .main,
        ) { [weak self] _ in
          Task { @MainActor in self?.emitCurrentState() }
        }
      }

      // Push the current state immediately so a just-subscribed JS consumer
      // isn't stuck on the default until the first transition.
      emitCurrentState()
    }

    // MARK: - State queries

    var castState: CastState {
      guard isConfigured else { return .noDevices }
      return CastStateMapper.map(GCKCastContext.sharedInstance().castState)
    }

    var deviceName: String? {
      currentSession?.device.friendlyName
    }

    var isCasting: Bool {
      currentSession?.connectionState == .connected
    }

    private var currentSession: GCKCastSession? {
      guard isConfigured else { return nil }
      return GCKCastContext.sharedInstance().sessionManager.currentCastSession
    }

    private var remoteClient: GCKRemoteMediaClient? {
      currentSession?.remoteMediaClient
    }

    /// Emit a Cast state to JS. Pass an explicit `state` from a session-lifecycle
    /// callback (derived from the session's own `connectionState`) because the
    /// context's `castState` is KVO-updated asynchronously and can still report
    /// the *previous* state at the instant the callback fires. With no argument we
    /// read the context state — correct for discovery-driven changes.
    private func emitCurrentState(_ state: CastState? = nil) {
      let resolved = state ?? castState
      let name = deviceName
      // Coalesce duplicate emits from the session-callback and discovery-observer
      // paths.
      guard coalescer.shouldEmit(state: resolved, deviceName: name) else { return }
      onStateChanged?(CastStateChangedEvent(state: resolved, deviceName: name))
    }

    // MARK: - Picker

    func showPicker() {
      guard isConfigured else {
        logger.info("showCastPicker: not configured — no-op")
        return
      }
      GCKCastContext.sharedInstance().presentCastDialog()
    }

    func endSession() {
      guard isConfigured else { return }
      GCKCastContext.sharedInstance().sessionManager.endSessionAndStopCasting(true)
    }

    /// Detach all GCK listeners (session manager + remote client) so the GCK
    /// process singletons don't keep a strong ref to this (now-dead) instance
    /// across a JS reload. Stops discovery if we still hold it. Does NOT end the
    /// Cast session itself — audio should keep playing on the device while a new
    /// instance comes up and (on resume) re-attaches. Idempotent.
    func tearDown() {
      guard isConfigured else { return }
      if let castStateObserver {
        NotificationCenter.default.removeObserver(castStateObserver)
        self.castStateObserver = nil
      }
      GCKCastContext.sharedInstance().sessionManager.remove(self)
      GCKCastContext.sharedInstance().sessionManager.currentCastSession?.remoteMediaClient?.remove(self)
      if discoverySubscribers > 0 {
        GCKCastContext.sharedInstance().discoveryManager.stopDiscovery()
        discoverySubscribers = 0
      }
    }

    // MARK: - Discovery ref-counting

    /// Increment the discovery subscriber count; start discovery on the 0→1 edge.
    func retainDiscovery() {
      discoverySubscribers += 1
      if discoverySubscribers == 1, isConfigured {
        GCKCastContext.sharedInstance().discoveryManager.startDiscovery()
        logger.debug("discovery started (subscribers=1)")
      }
    }

    /// Decrement the discovery subscriber count; stop discovery on the 1→0 edge.
    func releaseDiscovery() {
      guard discoverySubscribers > 0 else { return }
      discoverySubscribers -= 1
      if discoverySubscribers == 0, isConfigured {
        GCKCastContext.sharedInstance().discoveryManager.stopDiscovery()
        logger.debug("discovery stopped (subscribers=0)")
      }
    }

    // MARK: - Queue mirroring (delegated to CastQueueMirror)

    /// Reset re-sign + pending-seek accounting (this object's state) and hand the
    /// build/load off to `CastQueueMirror`, seeding the receiver start position.
    private func loadMirroredQueue() {
      guard let coordinator, remoteClient != nil else { return }
      reSign.reset()
      // A new load supersedes any seek captured against the previous media.
      pendingSeek = nil
      let startPosition = coordinator.castTransport === self ? remotePosition : 0
      queueMirror.loadMirroredQueue(startPosition: startPosition)
    }

    /// Clear pending-seek state, then have `CastQueueMirror` rebuild the local
    /// Queue from the receiver's mirrored items (cold-relaunch-while-casting).
    private func rehydrateQueueFromReceiver() {
      guard coordinator != nil, remoteClient != nil else { return }
      // Adopting the receiver's queue supersedes any seek captured beforehand.
      pendingSeek = nil
      queueMirror.rehydrateQueueFromReceiver()
    }
  }

  // MARK: - CastTransportDelegate (coordinator → Cast device)

  extension CastSessionManager: CastTransportDelegate {
    func castPlay() {
      remoteClient?.play()
    }

    func castPause() {
      remoteClient?.pause()
    }

    func castSeek(to seconds: Double) {
      guard let remoteClient else { return }
      // If the receiver has no seekable media yet, a seek would be silently dropped —
      // hold it and replay once the phase becomes seekable (see `didUpdate`).
      guard Self.castPhase(from: remoteClient.mediaStatus?.playerState).allowsSeek else {
        pendingSeek = seconds
        return
      }
      performSeek(on: remoteClient, to: seconds)
    }

    private func performSeek(on client: GCKRemoteMediaClient, to seconds: Double) {
      let options = GCKMediaSeekOptions()
      options.interval = seconds
      options.relative = false
      client.seek(with: options)
    }

    /// SDK boundary: map the GCK player state to the Cast-SDK-free
    /// `CastRemoteState.Phase` — the single receiver-status interpretation, shared
    /// by the state snapshot in `didUpdate` and seek-readiness (`Phase.allowsSeek`).
    private static func castPhase(from state: GCKMediaPlayerState?) -> CastRemoteState.Phase {
      switch state {
      case .playing: return .playing
      case .buffering: return .buffering
      case .loading: return .loading
      case .paused: return .paused
      case .idle: return .idle
      case .unknown, .none: return .idle
      @unknown default: return .idle
      }
    }

    func castSkipToCurrentIndex() {
      guard let coordinator, let remoteClient else { return }
      let index = coordinator.currentIndex
      let count = remoteClient.mediaQueue.itemCount
      guard index >= 0, index < Int(count),
            let item = remoteClient.mediaQueue.item(at: UInt(index), fetchIfNeeded: false)
      else {
        // The mirrored queue may not be loaded yet; (re)load it.
        loadMirroredQueue()
        return
      }
      remoteClient.queueJumpToItem(withID: item.itemID)
    }

    func castStop() {
      remoteClient?.stop()
    }

    func castSetVolume(_ level: Float) {
      currentSession?.setDeviceVolume(level)
    }

    var castCurrentTime: Double {
      remotePosition
    }

    var castDuration: Double {
      remoteDuration
    }
  }

  // MARK: - GCKSessionManagerListener

  extension CastSessionManager: GCKSessionManagerListener {
    func sessionManager(_: GCKSessionManager, didStart session: GCKSession) {
      handleSessionConnected(session, resumed: false)
    }

    func sessionManager(_: GCKSessionManager, didResumeSession session: GCKSession) {
      handleSessionConnected(session, resumed: true)
    }

    func sessionManager(_: GCKSessionManager, willEnd session: GCKSession) {
      logger.info("Cast session will end — handing playback back to local")
      // Detach the remote-client listener before the session tears down so the
      // GCK singleton doesn't keep a strong ref to us across the boundary.
      session.remoteMediaClient?.remove(self)
      handleSessionEnded()
    }

    func sessionManager(_: GCKSessionManager, didEnd session: GCKSession, withError error: Error?) {
      if let error { logger.error("Cast session ended with error: \(error.localizedDescription)") }
      session.remoteMediaClient?.remove(self)
      handleSessionEnded()
    }

    func sessionManager(_: GCKSessionManager, didFailToStart session: GCKSession, withError error: Error) {
      logger.error("Cast session failed to start: \(error.localizedDescription)")
      emitCurrentState(CastStateMapper.map(session.connectionState))
    }

    private func handleSessionConnected(_ session: GCKSession, resumed: Bool) {
      logger.info("Cast session \(resumed ? "resumed" : "started") on \(session.device.friendlyName ?? "device", privacy: .public)")
      guard let coordinator else { return }
      if !resumed {
        // Fresh connect: capture where local playback currently is BEFORE flipping
        // isRemote (after which the local position accessors report the remote
        // position, which is still 0). This seeds the receiver's start position and
        // gives a correct hand-back point should the session end before the first
        // remote status update lands. (On resume the receiver owns the position.)
        remotePosition = coordinator.effectHandler?.currentTime ?? 0
      }
      // Suspend local playback and become the transport (flips isRemote).
      coordinator.castTransport = self
      coordinator.suspendLocalPlayback()
      session.remoteMediaClient?.add(self)

      if resumed {
        // ADR-0003: cold-relaunch-while-casting rehydrates the full queue from
        // the receiver's mediaQueue customData. If our local queue is empty,
        // pull it back; otherwise re-load our mirror.
        if coordinator.tracks.isEmpty {
          rehydrateQueueFromReceiver()
        } else {
          loadMirroredQueue()
        }
      } else {
        loadMirroredQueue()
      }
      // Emit from the session's own connection state, not the context's `castState`
      // (which can still read `.connecting` at this instant).
      emitCurrentState(CastStateMapper.map(session.connectionState))
    }

    private func handleSessionEnded() {
      guard let coordinator else { return }
      // The session has ended — emit `.notConnected` directly rather than reading
      // the context's `castState`, which can still report `.connected` until its
      // KVO catches up. The discovery observer later refines this to `.noDevices`
      // if the device also went away.
      // Re-entrancy guard: willEnd + didEnd both fire; only hand back once (while
      // we are still the active transport).
      guard coordinator.castTransport === self else {
        emitCurrentState(.notConnected)
        return
      }
      // Hand transport back to the local AVPlayer. The coordinator's local path
      // resumes driving AVPlayer the moment castTransport is nil.
      let wasPlaying = coordinator.playWhenReady
      let resumePosition = remotePosition
      pendingSeek = nil
      coordinator.castTransport = nil
      // Continue on the phone at the last-known remote position (not from 0).
      coordinator.resumeLocalAfterCast(at: resumePosition, wasPlaying: wasPlaying)
      emitCurrentState(.notConnected)
    }
  }

  // MARK: - GCKRemoteMediaClientListener

  extension CastSessionManager: GCKRemoteMediaClientListener {
    func remoteMediaClient(_ client: GCKRemoteMediaClient, didUpdate mediaStatus: GCKMediaStatus?) {
      guard let mediaStatus else { return }
      remotePosition = client.approximateStreamPosition()
      remoteDuration = mediaStatus.mediaInformation?.streamDuration ?? 0

      let phase = Self.castPhase(from: mediaStatus.playerState)

      // Replay a seek that arrived before the receiver had seekable media, now that
      // the phase can accept one.
      if let seek = pendingSeek, phase.allowsSeek {
        pendingSeek = nil
        performSeek(on: client, to: seek)
      }

      // Map the receiver's current queue item id back to a queue INDEX so the
      // coordinator can follow auto-advances / external (TV remote, Google Home)
      // next-prev. nil when the receiver hasn't reported an item.
      let currentIndex = CastQueueMirror.receiverQueueIndex(for: mediaStatus.currentItemID, client: client)

      // Why the receiver is idle (only meaningful while idle; `.none` otherwise).
      let idleReason: CastRemoteState.IdleReason
      if mediaStatus.playerState == .idle {
        switch mediaStatus.idleReason {
        case .finished: idleReason = .finished
        case .cancelled: idleReason = .cancelled
        case .interrupted: idleReason = .interrupted
        case .error: idleReason = .error
        case .none: idleReason = .none
        @unknown default: idleReason = .none
        }
      } else {
        idleReason = .none
      }

      coordinator?.applyRemoteState(
        CastRemoteState(
          phase: phase,
          position: remotePosition,
          duration: remoteDuration,
          currentIndex: currentIndex,
          idleReason: idleReason,
        ),
      )
    }

    func remoteMediaClient(_ client: GCKRemoteMediaClient, didReceive _: GCKMediaError) {
      // A receiver-side media error — usually a stale signed URL on the item the
      // receiver is loading / advancing into, NOT necessarily the one playing.
      // Prefer `loadingItemID`; fall back to `preloadedItemID`, then the playing
      // item, skipping the invalid sentinel. Re-sign is bounded (CastReSign caps
      // attempts per item).
      let status = client.mediaStatus
      let candidate = [status?.loadingItemID, status?.preloadedItemID, status?.currentItemID]
        .compactMap { $0 }
        .first { $0 != kGCKMediaQueueInvalidItemID }
      // A re-sign was dispatched (or one is already in flight) — let it try to
      // recover before we treat this as terminal.
      if let itemID = candidate, reSign.handleLoadError(remoteClient: client, itemID: itemID) {
        return
      }
      // No re-signable item, or the per-item re-sign budget is exhausted: this is a
      // terminal receiver error. Surface it into the shared state machine so the
      // player doesn't sit silently stuck on the receiver's idle/error.
      coordinator?.applyRemoteError()
    }
  }

#endif
