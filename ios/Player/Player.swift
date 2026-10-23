import Foundation
import os.log

#if canImport(NitroModules)
  import NitroModules
#endif

/// The player: the playback state machine, the queue, retries, the sleep timer, persistence,
/// and every decision about loading, seeking and recovering. The media stack under it is a
/// `PlayerEngine` (AVPlayerEngine in production, a mock in tests), which carries the decisions out
/// and reports what it observes.
@MainActor
final class Player {
  let logger = Logger(subsystem: "com.audiobrowser", category: "Player")

  // MARK: - Dependencies

  let engine: any PlayerEngine
  weak var callbacks: PlayerCallbacks?

  let queue = QueueManager()
  let retry: RetryLoop
  let sleepTimerManager: SleepTimerManager
  var deferredSeek = DeferredSeek()

  private lazy var progressTimer = PlaybackTimer(
    isActive: { $0 == .loading || $0 == .buffering || $0 == .playing },
  ) { [weak self] in
    guard let self, queue.currentIndex >= 0 else { return }
    let progressEvent = PlaybackProgressUpdatedEvent(
      track: Double(queue.currentIndex),
      position: engine.currentTime,
      duration: engine.duration,
      buffered: engine.bufferedPosition,
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
  private(set) var errorStatus: ErrorStatus = .none

  /// One track's playback session, which automatic retry reloads keep (glossary: Load).
  struct Load: Equatable {
    let id: UInt
    /// Audio has rendered: retries get the recovery budget, not the first-connect one (ADR 0004).
    var hasPlayed = false
    /// The silent primer has run for this load's first failure (ADR 0005).
    var primedNowPlaying = false
    /// One of this load's items is in the engine; until then it reports the previous one's position.
    var itemPrepared = false

    /// True the first time it is asked, so the silent primer runs once per load.
    mutating func claimNowPlayingPrime() -> Bool {
      guard !primedNowPlaying else { return false }
      primedNowPlaying = true
      return true
    }
  }

  private(set) var load: Load?
  private var loadCount: UInt = 0

  /// Starts a Load: a track becoming current, or a restart of this one from a terminal error.
  /// Fresh retry budgets, no pending seek, unproven playback.
  private func beginLoad() {
    loadCount &+= 1
    load = Load(id: loadCount)
    retry.reset()
    deferredSeek.reset()
  }

  /// The play intent captured when the current interruption began; its end resumes only then.
  private var shouldResumeAfterInterruption = false

  /// Playback rate (1.0 = normal speed). A non-positive rate, which could never play, is refused.
  /// Speed and play intent are independent, as on Android and web.
  var rate: Float = 1.0 {
    didSet {
      guard rate > 0 else {
        rate = oldValue
        return
      }
      engine.setRate(rate, playing: playWhenReady)
      persistence.save()
    }
  }

  private let persistenceDefaults: UserDefaults
  private let persistenceSaveInterval: Duration
  private lazy var persistence = PlaybackPersistence(
    defaults: persistenceDefaults, saveInterval: persistenceSaveInterval,
  ) { [unowned self] in
    currentTrack.map {
      PlaybackPersistence.snapshot(
        of: $0, state: state, position: enginePositionIsCurrent ? engine.currentTime : deferredSeek.target ?? 0,
        repeatMode: repeatMode,
        shuffleEnabled: shuffleEnabled, rate: rate,
      )
    }
  }

  /// Whether the engine reports this Load's position: not before one of its items is prepared,
  /// nor while one reloads.
  private var enginePositionIsCurrent: Bool {
    state != .loading && load?.itemPrepared == true
  }

  /// Where a reload resumes this Load.
  private var resumePosition: TimeInterval? {
    enginePositionIsCurrent ? engine.resumePosition : deferredSeek.target
  }

  // MARK: - Track State (for active track changed events)

  private var lastIndex: Int = -1
  private var lastTrack: Track?

  // MARK: - TrackSelectionPlayer and NowPlayingSource

  var tracks: [Track] { queue.tracks }
  var queueSourcePath: String? { queue.queueSourcePath }
  var currentTrack: Track? { queue.currentTrack }
  var currentTime: Double { engine.currentTime }
  var nitroPlaybackError: PlaybackError? { errorStatus.nitroError }

  var repeatMode: RepeatMode {
    get { queue.repeatMode }
    set {
      guard queue.repeatMode != newValue else { return }
      queue.repeatMode = newValue
      engine.updateRemoteRepeatMode(newValue)
      // Persisted on each change, or a mode toggled while paused would miss the resume snapshot.
      persistence.save()
      pushSkipAvailability()
      callbacks?.playerDidChangeRepeatMode(
        RepeatModeChangedEvent(repeatMode: newValue),
      )
    }
  }

  var shuffleEnabled: Bool {
    get { queue.shuffleEnabled }
    set {
      guard queue.shuffleEnabled != newValue else { return }
      queue.shuffleEnabled = newValue
      engine.updateRemoteShuffleMode(newValue)
      persistence.save()
      pushSkipAvailability()
      callbacks?.playerDidChangeShuffleEnabled(newValue)
    }
  }

  private func pushSkipAvailability() {
    engine.updateSkipAvailability(canNext: queue.canNext, canPrevious: queue.canPrevious)
  }

  // MARK: - playWhenReady

  /// The intent to play, applied through `setPlayWhenReady`.
  private(set) var playWhenReady = false

  func setPlayWhenReady(_ newValue: Bool) {
    let changed = newValue != playWhenReady
    playWhenReady = newValue
    // A play during an interruption owns the intent: a later pause must not be auto-resumed.
    if newValue { shouldResumeAfterInterruption = false }

    // A terminal state has no item to play, or one parked at its end: reload, re-resolving a URL
    // that may have expired since. Only .error resumes from the last position.
    if newValue, state == .error || state == .stopped || state == .ended {
      reloadResolving(startFromCurrentTime: state == .error)
    }
    if state != .loading {
      if newValue { engine.play() } else { engine.pause() }
    }

    guard changed else { return }
    // A pause during the sleep fade ends the timer early, restoring the pre-fade volume.
    if !newValue, sleepTimerManager.isFading { sleepTimerManager.clear() }
    publish()
    evaluateSessionRelease()
  }

  /// Playing with intent, in a state that can play; buffering while loading or rebuffering.
  static func playingState(playWhenReady: Bool, state: PlaybackState) -> PlayingState {
    PlayingState(
      playing: playWhenReady && ![.error, .ended, .none, .stopped].contains(state),
      buffering: playWhenReady && (state == .loading || state == .buffering),
    )
  }

  var playingState: PlayingState {
    Self.playingState(playWhenReady: playWhenReady, state: state)
  }

  var playbackActive: Bool {
    switch state {
    case .none, .stopped, .ended, .error:
      false
    default: true
    }
  }

  // MARK: - Init

  /// - Parameters:
  ///   - persistenceDefaults: Where the resume snapshot is kept.
  ///   - persistenceSaveInterval: How often the snapshot is saved while the play intent is active.
  init(
    engine: any PlayerEngine,
    retry: RetryLoop = RetryLoop(),
    sleepTimerScheduler: any SleepTimerScheduling = MainQueueSleepTimerScheduler(),
    persistenceDefaults: UserDefaults = PlaybackPersistence.standardDefaults,
    persistenceSaveInterval: Duration = .seconds(5),
  ) {
    self.engine = engine
    self.retry = retry
    sleepTimerManager = SleepTimerManager(
      fader: VolumeFader(
        getVolume: { [weak engine] in engine?.volume ?? 1 },
        setVolume: { [weak engine] in engine?.volume = $0 },
      ),
      scheduler: sleepTimerScheduler,
    )
    self.persistenceDefaults = persistenceDefaults
    self.persistenceSaveInterval = persistenceSaveInterval
    engine.onEvent = { [weak self] in self?.engineDidEmit($0) }

    sleepTimerManager.onComplete = { [weak self] in self?.pause() }
    sleepTimerManager.onChanged = { [weak self] in self?.callbacks?.playerDidChangeSleepTimer($0) }

    retry.onError = { [weak self] in self?.transition(.errorOccurred($0)) }
    retry.onRetryingError = { [weak self] in self?.reportRetryingError($0) }
    retry.onRetry = { [weak self] in self?.reloadResolving(startFromCurrentTime: $0) }
    retry.shouldRetry = { [weak self] in self?.playWhenReady ?? false }
    retry.hasPlayed = { [weak self] in self?.load?.hasPlayed ?? false }
  }

  /// Shows a failure the retry loop is still working on over the current state: not a
  /// transition, and not a `playerDidError`, which is for terminal errors.
  private func reportRetryingError(_ error: TrackPlayerError.PlaybackError) {
    guard state.carriesAdvisoryError else { return }
    errorStatus = .retrying(error)
    publish()
  }

  // MARK: - Playback State Machine

  func transition(_ event: PlaybackEvent) {
    guard let newState = nextPlaybackState(from: state, on: event) else { return }
    let oldState = state
    errorStatus = errorStatus.after(event: event, from: oldState, to: newState)
    guard newState != oldState else {
      publish()
      return
    }
    state = newState
    applySideEffects(old: oldState, new: newState)
    publish()
    // While nothing plays, no periodic save runs: every transition rewrites the snapshot, so it
    // never keeps a position from before a track change or a seek.
    if !(playbackActive && playWhenReady) { persistence.save() }
    // Repeat modes never end the queue (matches web's endsQueue()): a repeat-track pause at the
    // track boundary (e.g. the end-of-track sleep timer) must not read as "playlist over".
    if newState == .ended, queue.isLastInPlaybackOrder, repeatMode == .off {
      callbacks?.playerDidEndQueue(
        PlaybackQueueEndedEvent(track: Double(queue.currentIndex), position: engine.currentTime),
      )
    }
  }

  private struct Published: Equatable {
    var state = PlaybackState.none
    var errorStatus = ErrorStatus.none
    var playWhenReady = false
    var playing = false
    var buffering = false
    var playIntentActive = false
  }

  private var published = Published()

  /// Emits what changed since the last publish, in order: the play intent, the playing state,
  /// the playback (state and error), the terminal error, the active play intent. The one place
  /// these leave the Player, so each goes out once per change.
  private func publish() {
    let playing = playingState
    let now = Published(
      state: state, errorStatus: errorStatus, playWhenReady: playWhenReady,
      playing: playing.playing, buffering: playing.buffering,
      playIntentActive: playbackActive && playWhenReady,
    )
    let old = published
    guard now != old else { return }
    published = now
    if now.playWhenReady != old.playWhenReady {
      callbacks?.playerDidChangePlayWhenReady(now.playWhenReady)
    }
    if now.playing != old.playing || now.buffering != old.buffering {
      callbacks?.playerDidChangePlayingState(playing)
    }
    if now.state != old.state || now.errorStatus != old.errorStatus {
      callbacks?.playerDidChangePlayback(Playback(state: now.state, error: now.errorStatus.nitroError))
    }
    let erred = now.state == .error, wasErred = old.state == .error
    if erred != wasErred || (erred && now.errorStatus != old.errorStatus) {
      callbacks?.playerDidError(PlaybackErrorEvent(error: now.errorStatus.nitroError))
    }
    if now.playIntentActive != old.playIntentActive {
      persistence.playIntentDidChange(active: now.playIntentActive)
    }
  }

  private func applySideEffects(old: PlaybackState, new: PlaybackState) {
    if new == .playing {
      retry.playbackBecameAudible()
    } else if old == .playing {
      retry.playbackStoppedBeingAudible()
    }

    switch new {
    case .ready:
      engine.applyTimePitchAlgorithm()
      if playWhenReady { engine.play() }
    case .loading:
      engine.applyTimePitchAlgorithm()
    case .ended:
      // A natural end exhausts the play intent; the setter also requests the session release.
      setPlayWhenReady(false)
    default: break
    }

    progressTimer.onPlaybackStateChanged(new)
    intervalTimer.onPlaybackStateChanged(new)
  }

  // MARK: - Player Actions

  func play() {
    setPlayWhenReady(true)
  }

  func pause() {
    setPlayWhenReady(false)
  }

  func togglePlayback() {
    setPlayWhenReady(!playWhenReady)
  }

  func stop() {
    // Before the intent drop: a pending retry would surface .error and hold the session.
    retry.cancel()
    shouldResumeAfterInterruption = false
    transition(.stopped)
    setPlayWhenReady(false)
    if currentTrack?.live != true { seek(to: 0) }
  }

  // MARK: - Audio Session Interruptions

  /// The output route went away (headphones unplugged): pause, and cancel any interruption
  /// resume, so a call ending cannot start the built-in speaker.
  func handleRouteDisconnected() {
    shouldResumeAfterInterruption = false
    pause()
  }

  /// iOS has already paused the AVPlayer, but that pause is swallowed while the intent holds, so
  /// the paused state is forced here.
  func handleInterruptionBegan() {
    // A nested interruption keeps the intent the first one captured.
    shouldResumeAfterInterruption = shouldResumeAfterInterruption || playWhenReady
    pause()
    if playbackActive, state != .loading {
      transition(.avPlayerPaused(hasAsset: engine.hasLoadedAsset))
    }
  }

  /// Whether the interruption's end will resume playback, so the session reactivates first.
  var willResumeAfterInterruption: Bool { shouldResumeAfterInterruption }

  func handleInterruptionEnded(shouldResume: Bool) {
    guard shouldResumeAfterInterruption else { return }
    shouldResumeAfterInterruption = false
    if shouldResume {
      play()
    } else {
      evaluateSessionRelease()
    }
  }

  /// Requests a session release once playback is deliberately stopped: no intent, no
  /// interruption to resume from, no retry pending. The host debounces it.
  private func evaluateSessionRelease() {
    guard !playWhenReady,
          !shouldResumeAfterInterruption,
          retry.pendingTask == nil
    else { return }
    callbacks?.playerShouldReleaseSession()
  }

  func getPlayback() -> Playback {
    Playback(state: state, error: errorStatus.nitroError)
  }

  /// Drops the intent before `action` or raises it after, as `playWhenReady` asks.
  private func handlePlayWhenReady(_ playWhenReady: Bool?, action: () throws -> Void) rethrows {
    if playWhenReady == false { setPlayWhenReady(false) }
    try action()
    if playWhenReady == true { setPlayWhenReady(true) }
  }

  // MARK: - Queue Methods

  func load(_ track: Track, playWhenReady: Bool? = nil) {
    handlePlayWhenReady(playWhenReady) {
      if queue.currentIndex == -1 {
        afterQueueMutation(queue.add([track], initialIndex: 0))
      } else {
        // In bounds, as -1 took the branch above.
        do {
          try queue.replace(queue.currentIndex, track)
          afterQueueMutation(.tracksAndCurrent)
        } catch {
          logger.error("load: replace at \(self.queue.currentIndex) failed: \(error)")
        }
      }
    }
  }

  func replace(_ index: Int, _ track: Track) throws {
    try afterQueueMutation(queue.replace(index, track))
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
      afterQueueMutation(
        queue.setQueue(newTracks, initialIndex: initialIndex, sourcePath: sourcePath),
        startingAt: startPositionMs.flatMap { $0 > 0 ? $0 / 1000 : nil },
      )
    }
  }

  func add(_ tracks: [Track], initialIndex: Int? = nil, playWhenReady: Bool? = nil) {
    handlePlayWhenReady(playWhenReady) {
      afterQueueMutation(queue.add(tracks, initialIndex: initialIndex ?? 0))
    }
  }

  func add(_ tracks: [Track], at index: Int) throws {
    try afterQueueMutation(queue.addAt(tracks, at: index))
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
    try afterQueueMutation(queue.remove(index))
  }

  func skipTo(_ index: Int, playWhenReady: Bool? = nil) throws {
    try handlePlayWhenReady(playWhenReady) {
      if index == queue.currentIndex {
        seek(to: 0)
      } else {
        try queue.skipTo(index)
        handleCurrentTrackChanged()
      }
    }
  }

  func move(fromIndex: Int, toIndex: Int) throws {
    try afterQueueMutation(queue.move(fromIndex: fromIndex, toIndex: toIndex))
  }

  func removeUpcomingTracks() {
    afterQueueMutation(queue.removeUpcomingTracks())
  }

  func replay() {
    seek(to: 0) { [weak self] succeeded in
      if succeeded { self?.play() }
    }
  }

  /// The one place a settled queue mutation is published: the new track list, then the
  /// current-track change, then next/previous availability.
  private func afterQueueMutation(_ change: QueueChange, startingAt start: TimeInterval? = nil) {
    if change.tracks { callbacks?.playerDidChangeQueue(queue.tracks) }
    if change.currentTrack {
      handleCurrentTrackChanged(startingAt: start)
    } else if change.tracks {
      pushSkipAvailability()
    }
  }

  func clear() {
    afterQueueMutation(queue.clear())
    unload()
    engine.clearNowPlaying()
  }

  // MARK: - Track Loading

  private func trackDidPlayToEnd() {
    sleepTimerManager.onTrackPlayedToEnd()

    if repeatMode == .track {
      // The end-of-track sleep timer has just cleared the intent: settle in .ended, not replay.
      if playWhenReady {
        replay()
      } else {
        transition(.trackEndedNaturally)
      }
    } else if repeatMode == .queue || !queue.isLastInPlaybackOrder {
      next()
    } else {
      transition(.trackEndedNaturally)
    }
  }

  /// Loads the new current track. `start` is held as the deferred seek, so the ready transition
  /// waits for it (no flash at 0) and the snapshot saved on the way records it.
  func handleCurrentTrackChanged(startingAt start: TimeInterval? = nil) {
    defer { pushSkipAvailability() }
    sleepTimerManager.onTrackChanged()

    engine.cancelLoading()
    beginLoad()
    if let start { deferredSeek.capture(start) }

    // Published by the .trackLoading or .trackUnloaded transition below, even when it is a no-op.
    errorStatus = .none

    let lastPosition = engine.currentTime
    let shouldContinuePlayback = playWhenReady
    if let currentTrack {
      engine.stopObservingItem()
      engine.pause()

      // Loading first, so the setter does not play the item that is gone.
      transition(.trackLoading)
      setPlayWhenReady(shouldContinuePlayback)

      engine.loadNowPlayingMetadata(for: currentTrack)
      persistence.save()

      guard let src = currentTrack.src else {
        logger.error("No src for track \(currentTrack.path ?? currentTrack.title)")
        clearItem()
        transition(.errorOccurred(.invalidSourceUrl("nil")))
        return
      }
      engine.resolveAndLoad(src: src, track: currentTrack)
    } else {
      unload()
      engine.clearNowPlaying()
    }

    let eventData = PlaybackActiveTrackChangedEvent(
      lastIndex: lastIndex == -1 ? nil : Double(lastIndex),
      lastTrack: lastTrack,
      lastPosition: lastPosition,
      index: queue.currentIndex == -1 ? nil : Double(queue.currentIndex),
      track: currentTrack,
    )
    callbacks?.playerDidChangeActiveTrack(eventData)
    lastTrack = currentTrack
    lastIndex = queue.currentIndex
  }

  // MARK: - Seeking

  /// Seeks the current item. While loading, the target is held until the item is ready and the
  /// ready transition waits for it (`DeferredSeek`); `completion` then reports false at once.
  func seek(to seconds: TimeInterval, completion: @escaping @MainActor (Bool) -> Void = { _ in }) {
    if state == .loading {
      deferredSeek.capture(seconds)
      completion(false)
    } else if engine.hasItem {
      seekItem(to: seconds, completion: completion)
    } else {
      completion(false)
    }
  }

  func seek(by offset: TimeInterval) {
    if state == .loading {
      seek(to: (deferredSeek.target ?? 0) + offset)
    } else if engine.hasItem {
      seek(to: engine.currentTime + offset)
    }
  }

  /// Jumps to the live edge: the end of the seekable window when there is one; without one
  /// (ICY) there is nothing to seek within, so it reconnects. No-op for a track that isn't live.
  func seekToLiveEdge() {
    guard currentTrack?.live == true, engine.hasItem else { return }
    if let edge = engine.liveEdge {
      engine.seek(to: edge) { _ in }
    } else {
      reloadResolving(startFromCurrentTime: false)
    }
  }

  /// A completion arriving after a track change belongs to the old item, and reports false
  /// without touching the new one's deferred seek.
  private func seekItem(to seconds: TimeInterval, completion: @escaping @MainActor (Bool) -> Void) {
    let generation = deferredSeek.generation
    engine.seek(to: seconds) { [weak self] finished in
      guard let self, deferredSeek.generation == generation else {
        completion(false)
        return
      }
      seekDidLand()
      completion(finished)
    }
  }

  /// Issues the deferred seek if one is pending; true when it did, so ready waits for it.
  private func beginDeferredSeek() -> Bool {
    guard let time = deferredSeek.begin() else { return false }
    seekItem(to: time) { _ in }
    return true
  }

  private func seekDidLand() {
    switch deferredSeek.land() {
    case .done:
      if state == .loading { transition(.loadSeekCompleted) }
    case let .reseek(time):
      seekItem(to: time) { _ in }
    case .none:
      break
    }
  }

  // MARK: - Reloading

  /// Loads the current track again, re-running the media-URL resolver, so a consumer whose
  /// resolver mints short-lived URLs or tokens recovers once they expire. A track without a `src`
  /// fails as an invalid source; with no current track, nothing loads.
  func reloadResolving(startFromCurrentTime: Bool) {
    guard let track = currentTrack else { return }
    guard let src = track.src else {
      transition(.errorOccurred(.invalidSourceUrl("nil")))
      return
    }
    prepareForReload(resumingAt: startFromCurrentTime ? resumePosition : nil)
    engine.resolveAndLoad(src: src, track: track)
  }

  /// A restart from a terminal error begins a new Load on a new AVPlayer; automatic retries
  /// never get here in .error, since the retry loop keeps the state non-terminal. `time` is held
  /// as the deferred seek before the loading transition saves the snapshot.
  private func prepareForReload(resumingAt time: TimeInterval?) {
    if state == .error {
      beginLoad()
      clearItem()
      engine.recreate()
    } else {
      engine.cancelLoading()
      engine.dropAsset()
      engine.pause()
    }
    if let time { deferredSeek.capture(time) }
    transition(.trackLoading)
  }

  private func clearItem() {
    engine.clearItem()
    deferredSeek.reset()
  }

  private func unload() {
    clearItem()
    transition(.trackUnloaded)
  }

  /// Recovers from a media-services reset: every AVPlayer and session handle is invalid, so a
  /// new player reloads the current track, keeping the play intent, so a live stream reconnects
  /// instead of going silent. No-op when nothing is loaded.
  func mediaServicesWereReset() {
    guard currentTrack != nil else { return }
    logger.info("Media services were reset — recreating player and reloading current track")
    clearItem()
    engine.recreate()
    reloadResolving(startFromCurrentTime: false)
  }

  // MARK: - Engine Events

  private func engineDidEmit(_ event: EngineEvent) {
    switch event {
    case let .itemPrepared(likelyToKeepUp):
      load?.itemPrepared = true
      if playWhenReady { engine.play() }
      if !beginDeferredSeek(), likelyToKeepUp { bufferBecameSufficient() }
    case let .loadFailed(error):
      primeNowPlayingIfNeeded()
      retry.handleError(error, context: .mediaLoad)
    case .unplayable:
      primeNowPlayingIfNeeded()
      transition(.errorOccurred(.trackWasUnplayable))
    case let .invalidSource(url):
      primeNowPlayingIfNeeded()
      transition(.errorOccurred(.invalidSourceUrl(url)))
    case let .failed(error, httpStatusCode):
      retry.handleError(error, context: .playback, httpStatusCode: httpStatusCode)
    case let .likelyToKeepUp(isLikely):
      guard isLikely, engine.hasItem, !beginDeferredSeek() else { return }
      bufferBecameSufficient()
    case let .timeControlStatus(status, waitingForNoItem):
      recoverIfAirPlayStalled(transitioningTo: status, waitingForNoItem: waitingForNoItem)
      timeControlStatusDidChange(status)
    case .audioDidStart:
      load?.hasPlayed = true
      if state != .loading { transition(.audioFrameDecoded) }
    case .playedToEnd:
      itemDidPlayToEnd()
    case .stalled:
      // Nudge, don't reconnect: a genuine drop is recovered by the retry and network paths.
      recoverFromStall(reconnectIfLive: false)
    }
  }

  /// The item can play through: ready, unless a seek still holds the ready transition.
  private func bufferBecameSufficient() {
    guard engine.hasLoadedAsset, !deferredSeek.defersReady, state != .playing else { return }
    transition(.bufferingSufficient)
  }

  /// While loading, a status change belongs to the previous item. A pause near the end is the
  /// end arriving, which `.playedToEnd` handles; one with the play intent is a buffer or a seek.
  private func timeControlStatusDidChange(_ status: PlayerTimeControlStatus) {
    guard state != .loading else { return }
    switch status {
    case .paused:
      let duration = engine.duration
      guard duration <= 0 || engine.currentTime < duration - 0.5 else { return }
      if !engine.hasLoadedAsset {
        if state != .stopped { transition(.avPlayerPaused(hasAsset: false)) }
      } else if state != .error, state != .stopped, !playWhenReady {
        transition(.avPlayerPaused(hasAsset: true))
      }
    case .waitingToPlayAtSpecifiedRate:
      if engine.hasLoadedAsset { transition(.avPlayerWaiting) }
    case .playing:
      transition(.avPlayerPlaying)
    }
  }

  /// A failed load renders no audio, and without it iOS never elects the app onto the
  /// now-playing surfaces (ADR 0005). Once per load: one burst either elects it or never will.
  private func primeNowPlayingIfNeeded() {
    guard playWhenReady, load?.claimNowPlayingPrime() == true else { return }
    engine.primeNowPlaying()
  }

  /// A genuine end advances the queue; a dropped live stream or an underrun reported as an end
  /// is recovered instead (see `EndOfTrackJudgement`).
  private func itemDidPlayToEnd() {
    let judgement = EndOfTrackJudgement(
      isLive: currentTrack?.live == true,
      currentTime: engine.currentTime,
      duration: engine.duration,
    )
    switch judgement.outcome {
    case .ended: trackDidPlayToEnd()
    case .stalled: recoverFromStall(reconnectIfLive: true)
    }
  }

  /// Recovers from a stall while the play intent holds: a live stream that ran out of data
  /// (`reconnectIfLive`) rejoins the edge; otherwise play() un-parks AVPlayer from waiting.
  private func recoverFromStall(reconnectIfLive: Bool) {
    guard playWhenReady else { return }
    if reconnectIfLive, currentTrack?.live == true {
      seekToLiveEdge()
    } else {
      engine.play()
    }
  }

  private var previousTimeControlStatus: PlayerTimeControlStatus?
  private var previousWaitingForNoItem = false

  /// AVPlayer can strand itself in .paused after a `.noItemToPlay` wait over AirPlay while the
  /// intent is to play: play() is re-issued on exactly that transition.
  private func recoverIfAirPlayStalled(transitioningTo current: PlayerTimeControlStatus, waitingForNoItem: Bool) {
    let stalled = AirPlayStallJudgement(
      previous: previousTimeControlStatus,
      current: current,
      previousWaitingReasonWasNoItemToPlay: previousWaitingForNoItem,
      isAirPlay: engine.isAirPlayRoute,
      playWhenReady: playWhenReady,
    ).shouldNudge
    previousTimeControlStatus = current
    previousWaitingForNoItem = current == .waitingToPlayAtSpecifiedRate && waitingForNoItem
    if stalled {
      logger.info("AirPlay waiting→paused stall detected — nudging play()")
      engine.play()
    }
  }

  // MARK: - Connectivity

  /// Re-establishes a stalled stream when connectivity returns. AVPlayer doesn't reliably report
  /// a live stream's mid-stream drop as a failure: it waits with no error, so the retry loop
  /// never engages. Only while stalled (.buffering) with play intent, so a pause or a retry that
  /// gave up isn't resumed; and not while the retry loop is itself waiting for the network,
  /// which owns that reload.
  func networkDidReturn() {
    guard playWhenReady, state == .buffering, !retry.isWaitingForNetwork else { return }
    logger.info("Connectivity restored while stalled — re-resolving to reconnect")
    reloadResolving(startFromCurrentTime: true)
  }

  /// Time playback must have progressed before a buffer dip counts as a stall, so the initial
  /// connect or a seek doesn't read as one.
  private static let stallGraceSeconds: TimeInterval = 0.5

  /// Ongoing playback is waiting for data. Mirrors Android's `stalled`, which ExoPlayer
  /// distinguishes natively; iOS has no rebuffer-vs-initial signal.
  var isStalled: Bool {
    guard engine.hasItem else { return false }
    return engine.currentTime > Self.stallGraceSeconds && playWhenReady && !engine.isPlaybackLikelyToKeepUp
  }

  // MARK: - Resume

  /// The last persisted session; nil when nothing was persisted, or its track has since become
  /// `disabled`, which never plays.
  func persistedSession() -> PersistedPlaybackState? {
    guard let snapshot = persistence.load() else {
      logger.error("resume: nothing persisted")
      return nil
    }
    guard snapshot.track.disabled != true else {
      logger.info("resume: persisted track is disabled — refusing")
      return nil
    }
    return snapshot
  }

  /// Plays a persisted session (a cold-start resume): its settings, then its queue — `expanded`,
  /// the expansion of the track's contextual path, or the track alone.
  func resume(_ snapshot: PersistedPlaybackState, queue expanded: (tracks: [Track], selectedIndex: Int)?) {
    let track = snapshot.track.toNitro()
    logger.info("resume: cold — restoring persisted track")
    // Settings first: `setQueue` builds the shuffle order, and the mode events then precede the
    // queue change, as on Android.
    repeatMode = RepeatMode(fromString: snapshot.repeatMode) ?? .off
    shuffleEnabled = snapshot.shuffleEnabled
    rate = snapshot.playbackSpeed
    let startMs = track.live == true ? nil : snapshot.positionMs
    if let expanded {
      setQueue(expanded.tracks, initialIndex: expanded.selectedIndex, startPositionMs: startMs, playWhenReady: true)
    } else {
      setQueue([track], initialIndex: 0, startPositionMs: startMs, playWhenReady: true)
    }
  }

  /// Plays what `TrackSelector` resolved a picked track to.
  func execute(_ intent: TrackSelector.PlaybackIntent) {
    switch intent {
    case let .skipTo(index):
      do {
        try skipTo(index, playWhenReady: true)
      } catch {
        logger.error("Failed to skip to track at index \(index): \(error)")
      }
    case let .setQueue(tracks, startIndex, sourcePath):
      setQueue(tracks, initialIndex: startIndex, playWhenReady: true, sourcePath: sourcePath)
    case let .loadTrack(track):
      load(track, playWhenReady: true)
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

extension Player: TrackSelectionPlayer, NowPlayingSource {}
