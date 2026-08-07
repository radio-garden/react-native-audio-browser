import Foundation
import os.log

#if canImport(NitroModules)
  import NitroModules
#endif

/// Owns the playback state machine, side effect dispatch, observer context guards,
/// and track management orchestration. Testable without AVFoundation by injecting
/// a `PlaybackEffectHandler` (TrackPlayer in production, mock in tests).
@MainActor
class PlaybackCoordinator {
  let logger = Logger(subsystem: "com.audiobrowser", category: "PlaybackCoordinator")

  // MARK: - Dependencies

  weak var effectHandler: PlaybackEffectHandler?
  weak var callbacks: PlaybackCoordinatorCallbacks?

  let queue = QueueManager()
  let errorHandler: PlaybackErrorHandler
  let sleepTimerManager: any SleepTimerHandling
  let loadSeekCoordinator = LoadSeekCoordinator()

  lazy var volumeFader = VolumeFader(
    getVolume: { [weak self] in self?.effectHandler?.volume ?? 1 },
    setVolume: { [weak self] level in self?.effectHandler?.volume = level },
  )

  lazy var playingStateManager: PlayingStateManager = PlayingStateManager { [weak self] state in
    self?.callbacks?.playerDidChangePlayingState(state)
  }

  private lazy var progressTimer = PlaybackTimer(
    isActive: { $0 == .loading || $0 == .buffering || $0 == .playing },
  ) { [weak self] in
    guard let self, currentIndex >= 0, let effectHandler else { return }
    let progressEvent = PlaybackProgressUpdatedEvent(
      track: Double(currentIndex),
      position: effectHandler.currentTime,
      duration: effectHandler.duration,
      buffered: effectHandler.bufferedPosition,
    )
    callbacks?.playerDidUpdateProgress(progressEvent)
  }

  private lazy var intervalTimer = PlaybackTimer(
    isActive: { $0 == .playing },
  ) { [weak self] in
    self?.callbacks?.playerDidFirePlaybackInterval()
  }

  // MARK: - State

  private(set) var state: PlaybackState = .none
  var playbackError: TrackPlayerError.PlaybackError?
  /// True while `playbackError` is advisory — the retry loop is still working
  /// on it and the state stays non-terminal. Terminal errors (state == .error)
  /// always carry false.
  private(set) var isRetryingError = false

  /// The JS-facing form of `playbackError`, carrying the retrying flag.
  func nitroPlaybackError() -> PlaybackError? {
    playbackError?.toNitroError(retrying: isRetryingError)
  }

  /// Whether playback should resume when the current audio-session interruption
  /// ends — set to the play intent captured at interruption start, so we only
  /// auto-resume if we were actually playing when interrupted.
  private var shouldResumeAfterInterruption = false

  /// Seconds of continuous audible playback after which the retry budget is
  /// refilled — proof the stream actually delivered audio, not a brief blip
  /// (mirrors Android's HEALTHY_PLAYBACK_MS). Without the refill a long-lived
  /// stream permanently loses retry after its first recovered error, because
  /// the window/attempt counters otherwise reset only on track change.
  /// Overridable in tests.
  var healthyPlaybackDuration: TimeInterval = 20
  private var healthyPlaybackTask: Task<Void, Never>?

  /// Playback rate (1.0 = normal speed).
  var rate: Float = 1.0

  /// Audio time pitch algorithm identifier (stored as a String to avoid AVFoundation import).
  var audioTimePitchAlgorithm: String = "TimeDomain"

  // MARK: - Track State (for active track changed events)

  var lastIndex: Int = -1
  var lastTrack: Track?

  // MARK: - Queue Forwarding Properties

  var tracks: [Track] { queue.tracks }
  var currentIndex: Int { queue.currentIndex }
  var currentTrack: Track? { queue.currentTrack }
  var queueSourcePath: String? { queue.queueSourcePath }
  var nextTracks: [Track] { queue.nextTracks }
  var previousTracks: [Track] { queue.previousTracks }
  var isLastInPlaybackOrder: Bool { queue.isLastInPlaybackOrder }
  var canNext: Bool { queue.canNext }
  var canPrevious: Bool { queue.canPrevious }

  /// The repeat mode for the queue player.
  var repeatMode: RepeatMode {
    get { queue.repeatMode }
    set {
      guard queue.repeatMode != newValue else { return }
      queue.repeatMode = newValue
      effectHandler?.updateRemoteRepeatMode(newValue)
      pushSkipAvailability() // repeat-all wrap changes next/previous availability
      callbacks?.playerDidChangeRepeatMode(
        RepeatModeChangedEvent(repeatMode: newValue),
      )
    }
  }

  /// Whether shuffle mode is enabled.
  var shuffleEnabled: Bool {
    get { queue.shuffleEnabled }
    set {
      guard queue.shuffleEnabled != newValue else { return }
      queue.shuffleEnabled = newValue
      effectHandler?.updateRemoteShuffleMode(newValue)
      pushSkipAvailability() // shuffle reorders playback → boundary changes
      callbacks?.playerDidChangeShuffleEnabled(newValue)
    }
  }

  /// Pushes current next/previous availability to the remote command center so
  /// CarPlay / lock-screen grey out the buttons when there's nowhere to skip.
  private func pushSkipAvailability() {
    effectHandler?.updateSkipAvailability(canNext: queue.canNext, canPrevious: queue.canPrevious)
  }

  // MARK: - playWhenReady

  var playWhenReady: Bool = false {
    didSet {
      // A play during an interruption takes ownership of the intent — the
      // user's subsequent pause must not be overridden by auto-resume.
      // (handleInterruptionEnded consumes its flag before its own play().)
      if playWhenReady { shouldResumeAfterInterruption = false }

      // Terminal states need a reload, not a bare play(): the item is gone
      // (.stopped/.error) or parked at its end (.ended), where startPlayback()
      // is a silent no-op. Only .error resumes from the last position.
      if playWhenReady == true, state == .error || state == .stopped || state == .ended {
        effectHandler?.reloadTrack(startFromCurrentTime: state == .error)
      }
      if state != .loading {
        if playWhenReady {
          effectHandler?.startPlayback()
        } else {
          effectHandler?.pausePlayback()
        }
      }

      if oldValue != playWhenReady {
        // An explicit pause during the sleep fade is the timer's goal arriving
        // early: clear the timer (restores the pre-fade volume via onFadeCancel).
        if !playWhenReady, volumeFader.isActive {
          sleepTimerManager.clear()
        }
        callbacks?.playerDidChangePlayWhenReady(playWhenReady)
        playingStateManager.update(playWhenReady: playWhenReady, state: state)
        // Reflect play/pause intent immediately — even while the new item is
        // still loading. Auto-publishing doesn't set the explicit now-playing
        // playback state, and the player's timeControlStatus can't report
        // "playing" yet during loading. Guarded to active states so a play()
        // from a terminal state doesn't leave a phantom "playing" button.
        if playbackActive {
          effectHandler?.updateNowPlayingState(playWhenReady: playWhenReady)
        }
        evaluateSessionRelease()
      }
    }
  }

  var playbackActive: Bool {
    switch state {
    case .none, .stopped, .ended, .error:
      false
    default: true
    }
  }

  // MARK: - Init

  init(errorHandler: PlaybackErrorHandler, sleepTimerManager: any SleepTimerHandling) {
    self.errorHandler = errorHandler
    self.sleepTimerManager = sleepTimerManager

    queue.delegate = self

    // Configure sleep timer
    sleepTimerManager.onComplete = { [weak self] in
      guard let self else { return }
      self.volumeFader.resolve { self.pause() }
    }
    sleepTimerManager.onFadeStart = { [weak self] duration in
      self?.volumeFader.start(duration: duration)
    }
    sleepTimerManager.onFadeCancel = { [weak self] in
      self?.volumeFader.cancel(restoringVolume: true)
    }

    // Wire error handler to state machine
    errorHandler.onError = { [weak self] error in
      self?.transition(.errorOccurred(error))
    }
    errorHandler.onRetryingError = { [weak self] error in
      self?.reportRetryingError(error)
    }
  }

  /// Surfaces a failure the retry loop is still working on: attached to the
  /// current (non-terminal) state so UIs can show the cause over the spinner.
  /// Not a state transition — the state machine never sees it — and not a
  /// `playerDidError`, which stays reserved for terminal errors.
  private func reportRetryingError(_ error: TrackPlayerError.PlaybackError) {
    // Only attach while the failure is what the listener is looking at (matches
    // Android). During .playing buffered audio is still audibly fine — the
    // advisory would replace the song line over working sound — and a
    // terminal/stopped state must not regain an advisory error.
    guard state == .loading || state == .buffering || state == .paused else { return }
    // Each failed attempt re-reports; identical repeats add nothing.
    if isRetryingError, playbackError == error { return }
    playbackError = error
    isRetryingError = true
    callbacks?.playerDidChangePlayback(
      Playback(state: state, error: nitroPlaybackError()),
    )
  }

  // MARK: - Playback State Machine

  func transition(_ event: PlaybackEvent) {
    guard let newState = nextPlaybackState(from: state, on: event) else { return }

    // Allow error-to-error transitions to update the error and emit callbacks,
    // even though the state enum value doesn't change.
    if newState == state, case let .errorOccurred(error) = event {
      playbackError = error
      isRetryingError = false
      callbacks?.playerDidChangePlayback(
        Playback(state: state, error: nitroPlaybackError()),
      )
      callbacks?.playerDidError(
        PlaybackErrorEvent(error: nitroPlaybackError()),
      )
      return
    }

    guard newState != state else { return }
    let oldState = state
    state = newState
    applySideEffects(old: oldState, new: newState, event: event)
    emitStateChange(old: oldState, new: newState)
  }

  private func applySideEffects(old: PlaybackState, new: PlaybackState, event: PlaybackEvent) {
    // Error lifecycle
    if old == .error, new != .error {
      playbackError = nil
    }
    // A retrying error rides along through loading/buffering, but any state
    // that proves recovery (.ready/.playing — data flowed) or abandons the
    // attempt (.stopped/.none/.ended) clears it. Runs before emitStateChange,
    // so the emitted Playback is already clean.
    if isRetryingError, new != .loading, new != .buffering, new != .paused {
      playbackError = nil
      isRetryingError = false
    }
    if case let .errorOccurred(error) = event {
      playbackError = error
      isRetryingError = false
    }

    // Retry-budget refill: sustained audible playback proves the stream
    // recovered, so the next error starts a fresh retry window. Leaving
    // .playing before the threshold cancels the pending refill.
    if new == .playing {
      healthyPlaybackTask?.cancel()
      healthyPlaybackTask = Task { [weak self] in
        guard let self else { return }
        try? await Task.sleep(nanoseconds: UInt64(healthyPlaybackDuration * 1_000_000_000))
        guard !Task.isCancelled else { return }
        // Refill only between episodes: resetRetry() cancels a pending retry,
        // which would silently kill an in-flight reconnect.
        guard errorHandler.pendingRetryTask == nil else { return }
        errorHandler.resetRetry()
      }
    } else if old == .playing {
      healthyPlaybackTask?.cancel()
      healthyPlaybackTask = nil
    }

    // State-specific effects
    switch new {
    case .ready:
      effectHandler?.setTimePitchingAlgorithmForCurrentItem()
      if playWhenReady { effectHandler?.startPlayback() }
    case .loading:
      effectHandler?.setTimePitchingAlgorithmForCurrentItem()
    case .ended:
      // A natural end exhausts the play intent — nothing is left to play.
      // Keeping playWhenReady true would invert the play/pause toggle, hold
      // the audio session forever (release is intent-gated), and arm
      // interruption auto-resume into silence. The setter also requests the
      // session release.
      playWhenReady = false
    default: break
    }

    // Reflect play/pause (auto-publishing doesn't set the explicit now-playing
    // playback state). Active states show the user's intent — incl. .loading, so
    // the button flips to "playing" the moment a play-intent load begins; terminal
    // states (error/stopped/ended) resolve to paused so the button never sticks on
    // "playing" after playback fails or stops.
    effectHandler?.updateNowPlayingState(playWhenReady: playbackActive && playWhenReady)

    progressTimer.onPlaybackStateChanged(new)
    intervalTimer.onPlaybackStateChanged(new)
    playingStateManager.update(playWhenReady: playWhenReady, state: new)
  }

  private func emitStateChange(old: PlaybackState, new: PlaybackState) {
    // Playback state change — always emitted
    callbacks?.playerDidChangePlayback(
      Playback(state: new, error: nitroPlaybackError()),
    )

    // Error callback — emitted when entering or leaving error state
    if new == .error || (old == .error && new != .error) {
      callbacks?.playerDidError(
        PlaybackErrorEvent(error: nitroPlaybackError()),
      )
    }

    // Queue ended — when playback ends on the last track. Repeat modes never
    // conceptually end the queue (matches web's endsQueue()): a repeat-track
    // pause at the track boundary (e.g. the end-of-track sleep timer) must not
    // read as "playlist over".
    if new == .ended, isLastInPlaybackOrder, repeatMode == .off, let effectHandler {
      callbacks?.playerDidEndQueue(
        PlaybackQueueEndedEvent(track: Double(currentIndex), position: effectHandler.currentTime),
      )
    }
  }

  // MARK: - Observer Context Guards

  func avPlayerDidChangeTimeControlStatus(_ status: PlayerTimeControlStatus) {
    // During loading, ignore stale timeControlStatus changes from old items.
    if state == .loading { return }

    switch status {
    case .paused:
      guard let effectHandler else { return }
      let currentState = state
      let currentTime = effectHandler.currentTime
      let duration = effectHandler.duration
      // Ignore pauses when near track end
      let nearTrackEnd = currentTime >= duration - 0.5 && duration > 0

      if nearTrackEnd {
        // Ignore - track completion will be handled by handleTrackDidPlayToEndTime
      } else if !effectHandler.hasLoadedAsset, currentState != .stopped {
        transition(.avPlayerPaused(hasAsset: false))
      } else if currentState != .error, currentState != .stopped {
        if !playWhenReady {
          transition(.avPlayerPaused(hasAsset: true))
        }
      }
    case .waitingToPlayAtSpecifiedRate:
      if effectHandler?.hasLoadedAsset == true {
        transition(.avPlayerWaiting)
      }
    case .playing:
      transition(.avPlayerPlaying)
    }
  }

  func avPlayerStatusDidFail(error: Error?, httpStatusCode: Int? = nil) {
    errorHandler.handleError(error, context: .playback, httpStatusCode: httpStatusCode)
  }

  func avItemStatusDidChange(_ status: PlayerItemStatus, error: Error?, httpStatusCode: Int? = nil) {
    if status == .failed {
      errorHandler.handleError(error, context: .playback, httpStatusCode: httpStatusCode)
    }
  }

  func audioDidStart() {
    // Don't override loading state
    if state == .loading { return }
    transition(.audioFrameDecoded)
  }

  func avItemDidUpdatePlaybackLikelyToKeepUp(_ playbackLikelyToKeepUp: Bool) {
    guard playbackLikelyToKeepUp else { return }
    guard effectHandler?.hasLoadedAsset == true else { return }

    if !loadSeekCoordinator.shouldDeferReadyTransition, state != .playing {
      logger.debug("avItemDidUpdatePlaybackLikelyToKeepUp → .ready")
      transition(.bufferingSufficient)
    }
  }

  // MARK: - Player Actions

  func play() {
    playWhenReady = true
  }

  func pause() {
    playWhenReady = false
  }

  func togglePlayback() {
    playWhenReady = !playWhenReady
  }

  func stop() {
    // Cancel before the intent drop: a surviving retry would surface .error
    // over the stop, and a pending task blocks the session release below.
    errorHandler.cancelPendingRetry()
    // A stop is terminal — interruption-end must not resume over it.
    shouldResumeAfterInterruption = false
    transition(.stopped)
    playWhenReady = false
  }

  // MARK: - Audio Session Interruptions

  /// The active output route disappeared (headphones unplugged, Bluetooth
  /// speaker off). A deliberate output loss: drop the intent and any pending
  /// interruption auto-resume, so a call ending can't blast the built-in
  /// speaker.
  func handleRouteDisconnected() {
    shouldResumeAfterInterruption = false
    pause()
  }

  /// An audio-session interruption began (a phone call, or another app such as
  /// Music/Spotify taking over playback). iOS has already paused our AVPlayer,
  /// but that pause arrives as a `timeControlStatus` change that's swallowed
  /// while `playWhenReady` is still true — so the state would otherwise stay
  /// `.playing` and any UI driven by it stays stuck. Drop the play intent and
  /// force the paused state, independent of notification delivery order.
  func handleInterruptionBegan() {
    // OR: a nested interruption's second .began sees the already-paused
    // intent and must not wipe the flag the first one captured.
    shouldResumeAfterInterruption = shouldResumeAfterInterruption || playWhenReady
    pause()
    if playbackActive, state != .loading {
      transition(.avPlayerPaused(hasAsset: effectHandler?.hasLoadedAsset ?? false))
    }
  }

  /// Whether an interruption-end will resume playback — the intent captured at
  /// interruption start. Lets the host gate side effects that must precede the
  /// resume (audio-session reactivation) on whether one will actually happen.
  var willResumeAfterInterruption: Bool { shouldResumeAfterInterruption }

  /// An audio-session interruption ended. Resume only if we were playing when it
  /// began and the system indicates resumption is appropriate (`shouldResume`).
  func handleInterruptionEnded(shouldResume: Bool) {
    guard shouldResumeAfterInterruption else { return }
    shouldResumeAfterInterruption = false
    if shouldResume {
      play()
    } else {
      // Interruption ended and we're staying paused: the session can be released now.
      evaluateSessionRelease()
    }
  }

  /// Requests an audio-session release when playback is deliberately stopped: play intent is off,
  /// it isn't an interruption-driven pause (those resume — `shouldResumeAfterInterruption` is set
  /// before the interruption's `pause()`, so this skips it), and no retry is pending (a live-stream
  /// reconnect keeps `playWhenReady` true and must keep the session). The host debounces and cancels
  /// on the next activation, so a quick pause→resume never actually releases.
  private func evaluateSessionRelease() {
    guard !playWhenReady,
          !shouldResumeAfterInterruption,
          errorHandler.pendingRetryTask == nil
    else { return }
    callbacks?.playerShouldReleaseSession()
  }

  func getPlayback() -> Playback {
    Playback(state: state, error: nitroPlaybackError())
  }

  func getRepeatMode() -> RepeatMode {
    repeatMode
  }

  func setRepeatMode(_ mode: RepeatMode) {
    repeatMode = mode
  }

  /// Handles the `playWhenReady` setting while executing a given action.
  func handlePlayWhenReady(_ playWhenReady: Bool?, action: () throws -> Void) rethrows {
    if playWhenReady == false {
      self.playWhenReady = false
    }

    try action()

    if playWhenReady == true {
      self.playWhenReady = true
    }
  }

  // MARK: - Queue Methods

  func load(_ track: Track, playWhenReady: Bool? = nil) {
    handlePlayWhenReady(playWhenReady) {
      if queue.currentIndex == -1 {
        let changed = queue.add([track], initialIndex: 0)
        if changed { handleCurrentTrackChanged() }
      } else {
        queue.replace(queue.currentIndex, track)
        handleCurrentTrackChanged()
      }
    }
  }

  func replace(_ index: Int, _ track: Track) {
    queue.replace(index, track)
  }

  func setQueue(
    _ newTracks: [Track],
    initialIndex: Int = 0,
    startPositionMs: Double? = nil,
    playWhenReady: Bool? = nil,
    sourcePath: String? = nil,
  ) {
    guard !newTracks.isEmpty else {
      clear()
      return
    }
    handlePlayWhenReady(playWhenReady) {
      queue.setQueue(newTracks, initialIndex: initialIndex, sourcePath: sourcePath)
      handleCurrentTrackChanged()
      // Start position is applied as part of the load: captured as a pending
      // seek that runs once the item is ready, deferring the ready/play
      // transition until it lands (no start-at-0 flash).
      if let ms = startPositionMs, ms > 0 {
        loadSeekCoordinator.capture(position: ms / 1000)
      }
    }
  }

  func add(_ tracks: [Track], initialIndex: Int? = nil, playWhenReady: Bool? = nil) {
    handlePlayWhenReady(playWhenReady) {
      let changed = queue.add(tracks, initialIndex: initialIndex ?? 0)
      if changed { handleCurrentTrackChanged() }
    }
  }

  func add(_ tracks: [Track], at index: Int) throws {
    let changed = try queue.addAt(tracks, at: index)
    if changed { handleCurrentTrackChanged() }
    pushSkipAvailability() // didSet pushed pre-settlement state
  }

  func next() {
    let result = queue.next()
    switch result {
    case .trackChanged: handleCurrentTrackChanged()
    case .sameTrackReplay: if playWhenReady { replay() }
    case .noChange: break
    }
  }

  func previous() {
    let result = queue.previous()
    switch result {
    case .trackChanged: handleCurrentTrackChanged()
    case .sameTrackReplay: if playWhenReady { replay() }
    case .noChange: break
    }
  }

  func remove(_ index: Int) throws {
    let changed = try queue.remove(index)
    if changed { handleCurrentTrackChanged() }
    pushSkipAvailability() // didSet pushed pre-settlement state
  }

  func skipTo(_ index: Int, playWhenReady: Bool? = nil) throws {
    try handlePlayWhenReady(playWhenReady) {
      if index == queue.currentIndex {
        effectHandler?.seekToStart()
      } else {
        try queue.skipTo(index)
        handleCurrentTrackChanged()
      }
    }
  }

  func move(fromIndex: Int, toIndex: Int) throws {
    let changed = try queue.move(fromIndex: fromIndex, toIndex: toIndex)
    if changed { handleCurrentTrackChanged() }
    // tracks.didSet pushed availability mid-mutation, before the pointer and
    // shuffle order settled — re-push the real post-mutation state.
    pushSkipAvailability()
  }

  func removeUpcomingTracks() {
    queue.removeUpcomingTracks()
    pushSkipAvailability() // didSet pushed pre-settlement state
  }

  func replay() {
    effectHandler?.replayCurrentTrack()
  }

  func clear() {
    let changed = queue.clear()
    if changed { handleCurrentTrackChanged() }
    effectHandler?.unloadTrack()
    effectHandler?.clearNowPlaying()
  }

  // MARK: - Track Loading

  func handleTrackDidPlayToEndTime() {
    sleepTimerManager.onTrackPlayedToEnd()

    if repeatMode == .track {
      // The end-of-track sleep pause lands just above and clears the intent;
      // without it, settle in .ended (where play() reloads) instead of
      // replaying past the sleep timer.
      if playWhenReady {
        replay()
      } else {
        transition(.trackEndedNaturally)
      }
    } else if repeatMode == .queue || !isLastInPlaybackOrder {
      next()
    } else {
      transition(.trackEndedNaturally)
    }
  }

  func handleCurrentTrackChanged() {
    sleepTimerManager.onTrackChanged()

    effectHandler?.cancelMediaLoading()
    loadSeekCoordinator.reset()

    errorHandler.resetRetry()

    if playbackError != nil {
      playbackError = nil
      // A retrying error can be cleared while already .loading — the
      // .trackLoading transition below then won't fire, so emit the clear
      // here or the old track's error lingers over the new one's spinner.
      if isRetryingError, state == .loading {
        isRetryingError = false
        callbacks?.playerDidChangePlayback(Playback(state: state, error: nil))
      }
      isRetryingError = false
    }

    let lastPosition = effectHandler?.currentTime ?? 0
    let shouldContinuePlayback = playWhenReady
    if let currentTrack {
      effectHandler?.stopObservingCurrentItem()
      effectHandler?.pausePlayback()

      // Set loading state before playWhenReady so the setter's guard
      // prevents a no-op startPlayback() on the now-nil item.
      transition(.trackLoading)

      // Ensure playWhenReady is set before loading to preserve playback state
      playWhenReady = shouldContinuePlayback

      effectHandler?.loadNowPlayingMetadata(for: currentTrack)

      // Validate source URL before handing off to MediaLoader
      guard let src = currentTrack.src else {
        logger.error("Failed to load track - no src")
        logger.error("  track.title: \(currentTrack.title)")
        logger.error("  track.url: \(currentTrack.url ?? "nil")")
        effectHandler?.clearCurrentItem()
        transition(.errorOccurred(.invalidSourceUrl("nil")))
        return
      }

      logger.debug("Loading track: \(currentTrack.title)")
      logger.debug("  track.url: \(currentTrack.url ?? "nil")")
      logger.debug("  track.src: \(src)")

      effectHandler?.loadTrack(src: src, track: currentTrack)
    } else {
      effectHandler?.unloadTrack()
      effectHandler?.clearNowPlaying()
    }

    let eventData = PlaybackActiveTrackChangedEvent(
      lastIndex: lastIndex == -1 ? nil : Double(lastIndex),
      lastTrack: lastTrack,
      lastPosition: lastPosition,
      index: currentIndex == -1 ? nil : Double(currentIndex),
      track: currentTrack,
    )
    callbacks?.playerDidChangeActiveTrack(eventData)
    lastTrack = currentTrack
    lastIndex = currentIndex
    pushSkipAvailability() // position moved → next/previous availability may change
  }

  // MARK: - Seek Completion (called by TrackPlayer after AVPlayer seek lands)

  func handleSeekCompleted(to seconds: Double, didFinish: Bool) {
    if state == .loading {
      logger.debug("[loadSeek] seek landed at \(seconds)s (finished=\(didFinish)) → .ready")
      transition(.loadSeekCompleted)
    }
  }

  // MARK: - Progress Updates

  func setProgressUpdateInterval(_ interval: TimeInterval?) {
    progressTimer.setInterval(interval)
  }

  func setPlaybackIntervalEnabled(_ enabled: Bool) {
    intervalTimer.setInterval(enabled ? 1 : nil)
  }
}

// MARK: - QueueManagerDelegate

extension PlaybackCoordinator: QueueManagerDelegate {
  func queueDidChangeTracks(_ tracks: [Track]) {
    callbacks?.playerDidChangeQueue(tracks)
    pushSkipAvailability() // queue contents changed → boundaries moved
  }
}

// MARK: - TrackSelectionPlayer

extension PlaybackCoordinator: TrackSelectionPlayer {}
