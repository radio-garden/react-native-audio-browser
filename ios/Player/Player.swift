import Foundation
import os.log

#if canImport(NitroModules)
  import NitroModules
#endif

/// Holds every playback decision and carries them out through its `PlayerEngine` (ADR 0012).
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
  private let scheduler: any SleepTimerScheduling

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

  /// One load of the current track: a track change or a restart from the terminal error begins
  /// one; a retry's reconnect keeps it.
  struct Load: Equatable {
    let id: UInt
    var primedNowPlaying = false
    var itemPrepared = false
    /// The connection is closed; the next play reloads.
    var reloadOnPlay = false

    mutating func claimNowPlayingPrime() -> Bool {
      guard !primedNowPlaying else { return false }
      primedNowPlaying = true
      return true
    }
  }

  private(set) var load: Load?
  private var loadCount: UInt = 0

  private func beginLoad() {
    cancelIdleLivePark()
    loadCount &+= 1
    load = Load(id: loadCount)
    retry.loadStarted()
    deferredSeek.reset()
  }

  private var shouldResumeAfterInterruption = false

  /// Ignores a rate of 0 or less. Never starts or stops playback, as on Android and web.
  var rate: Float = 1.0 {
    didSet {
      guard rate > 0 else {
        rate = oldValue
        return
      }
      // In .error the intent stays up over an item the retry gave up on.
      engine.setRate(rate, playing: playWhenReady && state != .error)
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
        of: $0, state: state, position: deferredSeek.target ?? (enginePositionIsCurrent ? engine.currentTime : 0),
        repeatMode: repeatMode,
        shuffleEnabled: shuffleEnabled, rate: rate,
      )
    }
  }

  /// The engine's item is not yet this load's, so its position and time-control reports are stale.
  private var awaitingItem: Bool {
    state == .loading || load?.reloadOnPlay == true
  }

  private var enginePositionIsCurrent: Bool {
    !awaitingItem && load?.itemPrepared == true
  }

  private var resumePosition: TimeInterval? {
    deferredSeek.target ?? (enginePositionIsCurrent ? engine.resumePosition : nil)
  }

  private var reloadPosition: TimeInterval? {
    state == .ended || state == .stopped ? nil : resumePosition
  }

  // MARK: - Track State (for active track changed events)

  private var lastIndex: Int = -1
  private var lastTrack: Track?

  // MARK: - TrackSelectionPlayer and NowPlayingSource

  var tracks: [Track] { queue.tracks }
  var queueVersion: Int { queue.version }
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

  private(set) var playWhenReady = false

  func setPlayWhenReady(_ newValue: Bool) {
    let changed = newValue != playWhenReady
    playWhenReady = newValue
    retry.playIntentChanged(newValue)
    // A play during an interruption owns the intent: a later pause is not auto-resumed.
    if newValue { shouldResumeAfterInterruption = false }

    let resumes = state == .error || load?.reloadOnPlay == true
    if newValue, resumes || state == .stopped || state == .ended {
      reloadResolving(startFromCurrentTime: resumes)
    }
    // While loading, the engine may still hold the previous track's item: `.itemPrepared` plays.
    if !newValue { engine.pause() } else if state != .loading { engine.play() }
    if changed, !newValue { parkIdleLiveStream() }
    if changed, currentTrack?.live == true, liveItemPausable {
      engine.capForwardBuffer(newValue ? nil : Self.pausedLiveBufferSeconds)
    }

    guard changed else { return }
    // A resume can find .buffering or .loading, with no transition to start the watch.
    if newValue, state.awaitsData { retry.startStallWatch() }
    // Left to run, the retry would give up on the dropped intent and surface .error.
    if !newValue, retry.pendingTask != nil { parkForReloadOnPlay() }
    // Ends the sleep timer early, restoring the pre-fade volume.
    if !newValue, sleepTimerManager.isFading { sleepTimerManager.clear() }
    publish()
    evaluateSessionRelease()
  }

  /// Mirrors `PlayingStateFactory` on web and Android (ADR 0003).
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

  init(
    engine: any PlayerEngine,
    callbacks: PlayerCallbacks? = nil,
    retry: RetryLoop = RetryLoop(),
    sleepTimerScheduler: any SleepTimerScheduling = MainQueueSleepTimerScheduler(),
    persistenceDefaults: UserDefaults = PlaybackPersistence.standardDefaults,
    persistenceSaveInterval: Duration = .seconds(5),
  ) {
    self.engine = engine
    self.callbacks = callbacks
    self.retry = retry
    self.scheduler = sleepTimerScheduler
    sleepTimerManager = SleepTimerManager(
      fader: VolumeFader(
        getVolume: { [weak engine] in engine?.volume ?? 1 },
        setVolume: { [weak engine] in engine?.volume = $0 },
        scheduler: sleepTimerScheduler,
      ),
      scheduler: sleepTimerScheduler,
    )
    self.persistenceDefaults = persistenceDefaults
    self.persistenceSaveInterval = persistenceSaveInterval
    engine.onEvent = { [weak self] in self?.engineDidEmit($0) }

    sleepTimerManager.onComplete = { [weak self] in self?.pause() }
    sleepTimerManager.onChanged = { [weak self] in self?.callbacks?.playerDidChangeSleepTimer($0) }

    retry.onError = { [weak self] error in
      guard let self else { return }
      // A connect in flight must neither play nor fail under the error.
      engine.cancelLoading()
      engine.stopObservingItem()
      transition(.errorOccurred(error))
    }
    retry.onRetryingError = { [weak self] in self?.reportRetryingError($0) }
    retry.onFailedWithoutIntent = { [weak self] in self?.parkForReloadOnPlay() }
    retry.onRetry = { [weak self] in self?.reloadResolving(startFromCurrentTime: $0, shortStartBuffer: true) }
    // Until the connect's item is prepared, the engine still holds the item it replaces.
    retry.bufferedPosition = { [weak self] in
      guard let self, connectionItemPrepared else { return 0 }
      return engine.bufferedPosition
    }
  }

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
    // While playing, persistence saves on its own interval; otherwise every transition does, so the
    // snapshot never keeps a position from before a track change or a seek.
    if !(playbackActive && playWhenReady) { persistence.save() }
    // Repeat modes never end the queue, as web's `endsQueue()`.
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

  // Every change reaches the callbacks through here, in this order, so each fires once per change.
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
      if now.playWhenReady { engine.cancelAudioSessionRelease() }
      callbacks?.playerDidChangePlayWhenReady(now.playWhenReady)
    }
    if now.playing != old.playing || now.buffering != old.buffering {
      callbacks?.playerDidChangePlayingState(playing)
      // On output, not at setup: activating at launch interrupts other apps' audio.
      if playing.playing || playing.buffering { engine.activateAudioSession() }
      engine.renderNowPlaying()
    }
    if now.state != old.state || now.errorStatus != old.errorStatus {
      callbacks?.playerDidChangePlayback(Playback(state: now.state, error: now.errorStatus.nitroError))
      engine.renderNowPlaying()
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
    // .loading starts no watch: the retry loop watches each connect itself.
    if new == .buffering, playWhenReady {
      retry.startStallWatch()
    } else if old.awaitsData, !new.awaitsData {
      retry.stopStallWatch()
    }

    switch new {
    case .ready:
      engine.applyTimePitchAlgorithm()
      if playWhenReady { engine.play() }
    case .loading:
      engine.applyTimePitchAlgorithm()
    case .ended:
      setPlayWhenReady(false)
    case .error:
      // A failed item can still be alive: data arriving later must not play it.
      engine.pause()
      retry.cancel()
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

  /// Plays unless playing; from an error or the end, that reloads.
  func togglePlayback() {
    setPlayWhenReady(!playingState.playing)
  }

  /// Keeps the queue: a live stream closes its connection, anything else rewinds to 0.
  func stop() {
    // Before the intent drops, which would park a pending retry for resuming where it failed.
    retry.cancel()
    shouldResumeAfterInterruption = false
    engine.clearTimedMetadata()
    // A deferred park leaves the previous track's item in the engine: closed too.
    if holdsLiveConnection || pendingIdleLivePark != nil {
      cancelIdleLivePark()
      closeLiveConnection()
    }
    // Before the transition, whose now-playing render reads the intent.
    setPlayWhenReady(false)
    transition(.stopped)
    load?.reloadOnPlay = false
    deferredSeek.reset()
    if currentTrack?.live != true { seek(to: 0) }
  }

  // MARK: - Audio Session Interruptions

  /// The output route went away: pauses, and drops any interruption resume, so a call ending
  /// cannot start the built-in speaker.
  func handleRouteDisconnected() {
    shouldResumeAfterInterruption = false
    pause()
  }

  /// iOS has already paused the AVPlayer, but the play intent hides that pause, so it is forced here.
  func handleInterruptionBegan() {
    // A nested interruption keeps the intent the first one captured.
    shouldResumeAfterInterruption = shouldResumeAfterInterruption || playWhenReady
    pause()
    if playbackActive, state != .loading, state != .paused {
      transition(.avPlayerPaused(hasAsset: engine.hasLoadedAsset))
    }
  }

  var willResumeAfterInterruption: Bool { shouldResumeAfterInterruption }

  /// `shouldResume` is the system's resume hint for the ended interruption.
  func handleInterruptionEnded(shouldResume: Bool) {
    guard shouldResumeAfterInterruption else { return }
    shouldResumeAfterInterruption = false
    if shouldResume {
      play()
    } else {
      evaluateSessionRelease()
    }
  }

  private func evaluateSessionRelease() {
    // A pending retry's reconnect needs the session.
    guard !playWhenReady,
          !shouldResumeAfterInterruption,
          retry.pendingTask == nil
    else { return }
    engine.scheduleAudioSessionRelease()
  }

  func getPlayback() -> Playback {
    Playback(state: state, error: errorStatus.nitroError)
  }

  // Runs a queue change under the caller's `playWhenReady`: false pauses before it, true plays after
  // it, and `toPlay` sees the coming intent meanwhile, so a live connect inside isn't parked.
  private func handlePlayWhenReady(_ playWhenReady: Bool?, action: () throws -> Void) rethrows {
    if playWhenReady == false { setPlayWhenReady(false) }
    let outerIntentFollows = playIntentFollows
    playIntentFollows = playWhenReady == true
    defer { playIntentFollows = outerIntentFollows }
    try action()
    if playWhenReady == true { setPlayWhenReady(true) }
  }

  private var playIntentFollows = false

  private var toPlay: Bool { playWhenReady || playIntentFollows }

  // MARK: - Queue Methods

  private func throwIfInvalid(_ tracks: [Track]) throws {
    if let track = tracks.first(where: { $0.src == nil && $0.path == nil }) {
      throw TrackPlayerError.QueueError.invalidTrack(title: track.title)
    }
  }

  /// Replaces the current track, or starts a one-track queue when none is current.
  /// Throws when the track has neither `src` nor `path`.
  func load(_ track: Track, playWhenReady: Bool? = nil) throws {
    try throwIfInvalid([track])
    handlePlayWhenReady(playWhenReady) {
      if queue.currentIndex == -1 {
        afterQueueMutation(queue.add([track], initialIndex: 0))
      } else {
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

  /// An empty queue clears the player. A `startPositionMs` of 0 or less starts at the beginning.
  func setQueue(
    _ newTracks: [Track],
    initialIndex: Int = 0,
    startPositionMs: Double? = nil,
    playWhenReady: Bool? = nil,
    sourcePath: String? = nil,
  ) throws {
    try throwIfInvalid(newTracks)
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

  /// `initialIndex` applies only when the queue was empty.
  func add(_ tracks: [Track], initialIndex: Int? = nil, playWhenReady: Bool? = nil) throws {
    try throwIfInvalid(tracks)
    handlePlayWhenReady(playWhenReady) {
      afterQueueMutation(queue.add(tracks, initialIndex: initialIndex ?? 0))
    }
  }

  func add(_ tracks: [Track], at index: Int) throws {
    try throwIfInvalid(tracks)
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

  /// Skipping to the current index rewinds it to 0.
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

  // Publishes a settled queue mutation: the tracks, then the current track, then skip availability.
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
      // Without the intent (the end-of-track sleep timer just cleared it), settle in .ended.
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

  /// Loads the new current track. `start` waits as the deferred seek, so ready does not flash 0.
  func handleCurrentTrackChanged(startingAt start: TimeInterval? = nil) {
    defer { pushSkipAvailability() }
    sleepTimerManager.onTrackChanged()

    engine.cancelLoading()
    beginLoad()
    if let start { deferredSeek.capture(start) }

    // Published by the transition below, even when it is a no-op.
    errorStatus = .none

    let lastPosition = engine.currentTime
    let shouldContinuePlayback = playWhenReady
    engine.nowPlayingTrackChanged()
    if let currentTrack {
      engine.stopObservingItem()
      engine.pause()

      // Loading first, so the setter does not play the item that is gone.
      transition(.trackLoading)
      setPlayWhenReady(shouldContinuePlayback)

      engine.loadNowPlayingMetadata(for: currentTrack)
      persistence.save()

      if let src = currentTrack.src {
        connect(src: src, track: currentTrack)
      } else {
        logger.error("No src for track \(currentTrack.path ?? currentTrack.title)")
        engine.clearItem()
        transition(.errorOccurred(.invalidSourceUrl("nil")))
      }
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
    engine.renderNowPlaying()
    lastTrack = currentTrack
    lastIndex = queue.currentIndex
  }

  // MARK: - Seeking

  /// While loading or in error, the seek waits for the item and `completion` gets false.
  /// On a closed live stream it does nothing.
  func seek(to seconds: TimeInterval, completion: @escaping @MainActor (Bool) -> Void = { _ in }) {
    if liveStreamClosed {
      completion(false)
    } else if awaitingItem || state == .error {
      deferredSeek.capture(seconds)
      completion(false)
    } else if engine.hasItem {
      seekItem(to: seconds, completion: completion)
    } else {
      completion(false)
    }
  }

  func seek(by offset: TimeInterval) {
    if liveStreamClosed {
      return
    } else if awaitingItem || state == .error {
      seek(to: (resumePosition ?? 0) + offset)
    } else if engine.hasItem {
      seek(to: engine.currentTime + offset)
    }
  }

  /// Without a seekable window (ICY), or in error, whose window is stale, reconnects instead.
  /// A track that is not live, or a closed live stream, ignores it.
  func seekToLiveEdge() {
    guard currentTrack?.live == true, engine.hasItem, !liveStreamClosed else { return }
    if let edge = engine.liveEdge, state != .error {
      seek(to: edge)
    } else {
      reloadResolving(startFromCurrentTime: false)
    }
  }

  // A completion arriving after a track change is the old item's: false, and the new one's
  // deferred seek is left alone.
  private func seekItem(to seconds: TimeInterval, completion: @escaping @MainActor (Bool) -> Void) {
    let generation = deferredSeek.generation
    retry.seekStarted()
    engine.seek(to: seconds) { [weak self] finished in
      guard let self, deferredSeek.generation == generation else {
        completion(false)
        return
      }
      seekDidLand(finished: finished)
      completion(finished)
    }
  }

  // True when a deferred seek went out, so ready waits for it.
  private func beginDeferredSeek() -> Bool {
    guard let time = deferredSeek.begin() else { return false }
    seekItem(to: time) { _ in }
    return true
  }

  private func seekDidLand(finished: Bool) {
    switch deferredSeek.land(finished: finished) {
    case .done:
      if state == .loading { transition(.loadSeekCompleted) }
    case let .reseek(time):
      seekItem(to: time) { _ in }
    case .none:
      break
    }
  }

  // MARK: - Reloading

  /// Loads the current track again, re-running the media-URL resolver, so a short-lived URL or
  /// token is minted anew. A track without a `src` fails as an invalid source. `shortStartBuffer`
  /// is for the retry loop's reconnects (see `PlayerEngine.resolveAndLoad`).
  func reloadResolving(startFromCurrentTime: Bool, shortStartBuffer: Bool = false) {
    guard let track = currentTrack else { return }
    guard let src = track.src else {
      transition(.errorOccurred(.invalidSourceUrl("nil")))
      return
    }
    prepareForReload(resumingAt: startFromCurrentTime ? reloadPosition : nil)
    connect(src: src, track: track, shortStartBuffer: shortStartBuffer)
  }

  // Per connection, unlike `load.itemPrepared`: until a reconnect's item is prepared, the engine's
  // buffer is the old item's.
  private var connectionItemPrepared = false

  private func connect(src: String, track: Track, shortStartBuffer: Bool = false) {
    // A live stream is not connected without a play intent: it would buffer audio nobody hears.
    if track.live == true, !toPlay { return deferIdleLivePark() }
    connectionItemPrepared = false
    retry.connectionStarted()
    engine.resolveAndLoad(src: src, track: track, shortStartBuffer: shortStartBuffer)
  }

  /// Reloads now where it was, in place of any pending retry. A closed live stream waits for play.
  func retryNow() {
    guard !liveStreamClosed || toPlay else { return }
    retry.cancel()
    reloadResolving(startFromCurrentTime: true)
  }

  private func parkForReloadOnPlay() {
    retry.cancel()
    if let position = reloadPosition { deferredSeek.capture(position) }
    load?.reloadOnPlay = true
    transition(.retryCancelled)
  }

  // A paused AVPlayer goes on reading a live connection (on macOS for as long as it stays paused),
  // and a resume would play what it buffered, minutes behind. Closed, the next play reconnects at
  // the live edge.
  private func parkIdleLiveStream() {
    guard holdsLiveConnection, !toPlay else { return }
    closeLiveConnection()
    transition(.retryCancelled)
  }

  /// How long a live track loaded without a play intent waits for one before it closes.
  static let idleLiveParkDelaySeconds: TimeInterval = 1
  /// A live stream with a seekable window this long pauses in place; a shorter one closes on pause.
  static let pausableLiveWindowSeconds: TimeInterval = 30
  /// How far ahead a paused, pausable live stream keeps buffering.
  static let pausedLiveBufferSeconds: TimeInterval = 10

  private var liveItemPausable: Bool {
    (engine.liveWindowSeconds ?? 0) >= Self.pausableLiveWindowSeconds
  }

  private var pendingIdleLivePark: (any SleepTimerJob)?

  // Stays .loading with no connection, so a play sent as a separate call (`setQueue`, then `play()`)
  // connects with no pause between.
  private func deferIdleLivePark() {
    retry.cancel()
    engine.cancelLoading()
    load?.reloadOnPlay = true
    cancelIdleLivePark()
    pendingIdleLivePark = scheduler.schedule(after: Self.idleLiveParkDelaySeconds) { [weak self] in
      guard let self else { return }
      pendingIdleLivePark = nil
      engine.replaceItemWithNowPlayingCarrier()
      transition(.retryCancelled)
    }
  }

  private func cancelIdleLivePark() {
    pendingIdleLivePark?.cancel()
    pendingIdleLivePark = nil
  }

  // A retry that gave up leaves its item connected, so .error holds one too.
  private var holdsLiveConnection: Bool {
    currentTrack?.live == true && !liveItemPausable && (playbackActive || state == .error)
      && load?.reloadOnPlay != true
  }

  private var liveStreamClosed: Bool {
    currentTrack?.live == true && (load?.reloadOnPlay == true || state == .stopped && !liveItemPausable)
  }

  private func closeLiveConnection() {
    retry.cancel()
    engine.cancelLoading()
    engine.replaceItemWithNowPlayingCarrier()
    load?.reloadOnPlay = true
  }

  private func prepareForReload(resumingAt time: TimeInterval?) {
    cancelIdleLivePark()
    load?.reloadOnPlay = false
    let target = time ?? deferredSeek.target
    deferredSeek.reset()
    // A restart from the terminal error is a new Load, with fresh retry budgets (ADR 0014), on a
    // new AVPlayer; a retry gets a new AVPlayer only when the AVPlayer itself failed.
    let restart = state == .error
    if restart { beginLoad() }
    if restart || engine.playerFailed {
      engine.clearItem()
      engine.recreate()
    } else {
      engine.cancelLoading()
      engine.stopObservingItem()
      engine.pause()
    }
    if let target { deferredSeek.capture(target) }
    transition(.trackLoading)
  }

  private func unload() {
    engine.clearItem()
    transition(.trackUnloaded)
  }

  /// Every AVPlayer and session handle is now invalid: reloads the current track on a new
  /// AVPlayer, keeping the play intent and, unless live, the position.
  func mediaServicesWereReset() {
    guard let track = currentTrack else { return }
    logger.info("Media services were reset — recreating player and reloading current track")
    retry.cancel()
    if track.live != true, let position = reloadPosition {
      deferredSeek.capture(position)
    }
    engine.clearItem()
    engine.recreate()
    reloadResolving(startFromCurrentTime: false)
  }

  // MARK: - Engine Events

  private func engineDidEmit(_ event: EngineEvent) {
    switch event {
    case let .itemPrepared(likelyToKeepUp):
      load?.itemPrepared = true
      connectionItemPrepared = true
      if playWhenReady { engine.play() }
      if !beginDeferredSeek(), likelyToKeepUp { bufferBecameSufficient() }
    case let .loadFailed(error):
      // After the terminal error, a failure is the same connection's, reported late.
      guard state != .error else { return }
      primeNowPlayingIfNeeded()
      retry.handleError(error, context: .mediaLoad)
    case .unplayable:
      primeNowPlayingIfNeeded()
      transition(.errorOccurred(.trackWasUnplayable))
    case let .invalidSource(url):
      primeNowPlayingIfNeeded()
      transition(.errorOccurred(.invalidSourceUrl(url)))
    case let .failed(error, httpStatusCode):
      guard state != .error else { return }
      retry.handleError(error, context: .playback, httpStatusCode: httpStatusCode)
    case let .likelyToKeepUp(isLikely):
      guard isLikely, engine.hasItem, !beginDeferredSeek() else { return }
      bufferBecameSufficient()
    case let .timeControlStatus(status, waitingForNoItem):
      recoverIfAirPlayStalled(transitioningTo: status, waitingForNoItem: waitingForNoItem)
      timeControlStatusDidChange(status)
    case .audioDidStart:
      if !awaitingItem { transition(.audioFrameDecoded) }
    case .playedToEnd:
      itemDidPlayToEnd()
    case .stalled:
      // A nudge only: this also arrives while audio plays, so the stall watch keys on the state.
      if playWhenReady, state != .error { engine.play() }
    case let .timedMetadata(metadata):
      callbacks?.playerDidReceiveTimedMetadata(metadata)
      engine.applyTimedMetadata(metadata)
    }
  }

  private func bufferBecameSufficient() {
    guard engine.hasLoadedAsset, !deferredSeek.defersReady, state != .playing else { return }
    transition(.bufferingSufficient)
  }

  // While the load awaits its item, a status change is the replaced item's. A pause with the play
  // intent is a buffer or a seek.
  private func timeControlStatusDidChange(_ status: PlayerTimeControlStatus) {
    guard !awaitingItem else { return }
    switch status {
    case .paused:
      let duration = engine.duration
      // A pause at the end is the item ending: `.playedToEnd` handles it.
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

  // Once per load, only with a play intent: one burst elects the app or never will (ADR 0005).
  private func primeNowPlayingIfNeeded() {
    guard playWhenReady, load?.claimNowPlayingPrime() == true else { return }
    engine.primeNowPlaying()
  }

  private func itemDidPlayToEnd() {
    // An on-demand item's end is the end: a truncated file stalls instead. A live item's is a dropped
    // connection, for the retry loop, since AVPlayer does not play on from an ended item.
    guard currentTrack?.live == true else { return trackDidPlayToEnd() }
    // An end delivered during a load is the replaced item's.
    guard state != .loading else { return }
    // AVPlayer pauses an ended item, which the play intent hides: the state would read playing.
    if playWhenReady, retry.isEnabled { transition(.avPlayerWaiting) }
    retry.handleFailure(.liveEnd)
  }

  private var previousTimeControlStatus: PlayerTimeControlStatus?
  private var previousWaitingForNoItem = false

  // Outside the retry loop: nothing failed, and play() is the whole recovery.
  private func recoverIfAirPlayStalled(transitioningTo current: PlayerTimeControlStatus, waitingForNoItem: Bool) {
    let stalled = AirPlayStallJudgement(
      previous: previousTimeControlStatus,
      current: current,
      previousWaitingReasonWasNoItemToPlay: previousWaitingForNoItem,
      isAirPlay: engine.isAirPlayRoute,
      playWhenReady: playWhenReady && state != .error,
    ).shouldNudge
    previousTimeControlStatus = current
    previousWaitingForNoItem = current == .waitingToPlayAtSpecifiedRate && waitingForNoItem
    if stalled {
      logger.info("AirPlay waiting→paused stall detected — nudging play()")
      engine.play()
    }
  }

  // MARK: - Connectivity

  func networkStatusDidChange(isOnline: Bool) {
    // The stall line tells offline from buffering.
    engine.renderNowPlaying()
    retry.onlineChanged(isOnline)
  }

  // So the first connect or a seek does not read as a stall.
  private static let stallGraceSeconds: TimeInterval = 0.5

  /// Ongoing playback is waiting for data. Mirrors Android's `stalled`; iOS has no native
  /// rebuffer signal.
  var isStalled: Bool {
    guard engine.hasItem else { return false }
    return engine.currentTime > Self.stallGraceSeconds && playWhenReady && !engine.isPlaybackLikelyToKeepUp
  }

  // MARK: - Resume

  /// The persisted session, or nil when there is none or its track is disabled.
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

  /// Plays a persisted session: its settings, then `expanded` (the expansion of the track's
  /// contextual path) or the track alone.
  func resume(_ snapshot: PersistedPlaybackState, queue expanded: (tracks: [Track], selectedIndex: Int)?) {
    let track = snapshot.track.toNitro()
    logger.info("resume: cold — restoring persisted track")
    // Settings first: `setQueue` builds the shuffle order, and the mode events precede the queue
    // change, as on Android.
    repeatMode = RepeatMode(fromString: snapshot.repeatMode) ?? .off
    shuffleEnabled = snapshot.shuffleEnabled
    rate = snapshot.playbackSpeed
    let startMs = track.live == true ? nil : snapshot.positionMs
    do {
      if let expanded {
        try setQueue(expanded.tracks, initialIndex: expanded.selectedIndex, startPositionMs: startMs, playWhenReady: true)
      } else {
        try setQueue([track], initialIndex: 0, startPositionMs: startMs, playWhenReady: true)
      }
    } catch {
      logger.error("resume: \(error)")
    }
  }

  func execute(_ intent: TrackSelector.PlaybackIntent) {
    do {
      switch intent {
      case let .skipTo(index):
        try skipTo(index, playWhenReady: true)
      case let .setQueue(tracks, startIndex, sourcePath):
        try setQueue(tracks, initialIndex: startIndex, playWhenReady: true, sourcePath: sourcePath)
      case let .loadTrack(track):
        try load(track, playWhenReady: true)
      }
    } catch {
      logger.error("Failed to play the selected track: \(error)")
    }
  }

  // MARK: - Now Playing Preview

  /// Shows `track` on the now-playing surfaces before it plays; `endNowPlayingPreview()` reverts.
  func previewNowPlaying(_ track: Track) {
    engine.loadNowPlayingMetadata(for: track)
  }

  func endNowPlayingPreview() {
    guard let currentTrack else {
      engine.clearNowPlaying()
      return
    }
    engine.loadNowPlayingMetadata(for: currentTrack)
    engine.renderNowPlaying()
  }

  // MARK: - Progress Updates

  /// nil stops the progress updates.
  func setProgressUpdateInterval(_ interval: TimeInterval?) {
    progressTimer.setInterval(interval)
  }

  func setPlaybackIntervalEnabled(_ enabled: Bool) {
    intervalTimer.setInterval(enabled ? 1 : nil)
  }
}

extension Player: TrackSelectionPlayer, NowPlayingSource {}
