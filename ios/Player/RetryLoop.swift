import AVFoundation
import Foundation
#if canImport(NitroModules)
  import NitroModules
#endif
import os.log

/// Where a failure came from, which decides where the retry resumes and the error reported when
/// the failure cannot be classified.
public enum PlaybackErrorContext {
  /// An AVPlayer/AVPlayerItem failure: retries resume from the current time.
  case playback
  /// A MediaLoader key-value load failure: retries start from the beginning.
  case mediaLoad
  /// Playback that stopped receiving data without AVFoundation failing it: retries resume from
  /// the current time.
  case stall
  /// A live stream that reported played-to-end, as a cleanly closed connection does: retries
  /// reconnect, since AVPlayer will not play on from an item it has ended.
  case liveEnd

  var startFromCurrentTime: Bool {
    switch self {
    case .playback, .stall: true
    case .mediaLoad, .liveEnd: false
    }
  }

  var fallbackError: TrackPlayerError.PlaybackError {
    switch self {
    case .playback: .playbackFailed
    case .mediaLoad: .failedToLoadKeyValue
    case .stall, .liveEnd: .playbackStalled
    }
  }
}

/// Milliseconds on a clock that does not jump, and sleeps on it.
protocol RetryClock: Sendable {
  var nowMs: Int { get }
  func sleep(ms: Int) async throws
}

/// Counts while the device sleeps, so a silence spans it.
struct ContinuousRetryClock: RetryClock {
  private let origin = ContinuousClock.now
  var nowMs: Int { Int((ContinuousClock.now - origin) / .milliseconds(1)) }
  func sleep(ms: Int) async throws { try await Task.sleep(for: .milliseconds(ms)) }
}

/// Reconnects failed loads, paced by `ReconnectPacer`, and gives up when `SilenceBudget`'s
/// deadline passes (docs/adr/0014), reporting the last failure as terminal; the iOS counterpart
/// of Android's RetryLoadErrorHandlingPolicy. The play intent and connectivity go to both units.
@MainActor
final class RetryLoop {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "RetryLoop")

  private(set) var isEnabled = false
  private var playIntent = false
  private var budget: SilenceBudget
  private var pacer: ReconnectPacer
  private var connectionStartedAtMs: Int?
  private var connectionDeliveredAudio = false
  /// AVFoundation can report one connection's failure more than once; the units hear its end once.
  private var connectionEndReported = false
  private var replacedConnectionCounts = false
  /// True from a connection's start until a cancel or a terminal error, so a give-up reports one
  /// error, and a stop, or a play with nothing loaded, leaves nothing to give up on.
  private var deadlineArmed = false
  private var lastFailure: TrackPlayerError.PlaybackError?
  private var deadlineTask: Task<Void, Never>?

  /// Reloads; the argument is true to resume from the current time, false to start over.
  var onRetry: ((Bool) -> Void)?
  /// Terminal: the deadline passed, `maxRetries` connections in a row delivered nothing, or the
  /// failure cannot be retried. Not called when the retry is cancelled.
  var onError: ((TrackPlayerError.PlaybackError) -> Void)?
  /// A failure a retry is now scheduled for, reported before the wait while the state stays
  /// non-terminal.
  var onRetryingError: ((TrackPlayerError.PlaybackError) -> Void)?
  /// A retryable failure with no play intent: nothing retries it and nothing reports it.
  var onFailedWithoutIntent: (() -> Void)?

  /// The retry in flight; session release waits while there is one.
  private(set) var pendingTask: Task<Void, Never>?

  /// How long a stall may go without new data before it counts as a failure; longer than AVPlayer
  /// takes to fail a stalled item itself, so its error, which says more, is the one usually retried.
  var stallTimeout: Duration = .seconds(30)
  /// The end of the loaded range; the stall watch reads progress off it.
  var bufferedPosition: () -> Double = { 0 }
  private(set) var stallTask: Task<Void, Never>?

  private let clock: any RetryClock
  private let sleep: @Sendable (Int) async throws -> Void

  /// - Parameters:
  ///   - online: Connectivity when the loop is made; `onlineChanged` reports each change after.
  ///   - sleep: The wait before a reconnect, in milliseconds; the clock's sleep by default.
  init(
    online: Bool = true,
    clock: any RetryClock = ContinuousRetryClock(),
    sleep: (@Sendable (Int) async throws -> Void)? = nil,
  ) {
    budget = SilenceBudget(online: online)
    pacer = ReconnectPacer(online: online)
    self.clock = clock
    self.sleep = sleep ?? { try await clock.sleep(ms: $0) }
  }

  /// Whether the current Load has produced audio (docs/adr/0004).
  var hasPlayed: Bool { budget.hasPlayed }

  // MARK: - Configuration

  func updatePolicy(from config: Variant_Bool_RetryConfig?) {
    budget.recoveryMs = 120_000
    budget.firstConnectMs = 12_000
    pacer.maxRetries = nil
    switch config {
    case nil:
      isEnabled = false
    case let .first(enabled):
      isEnabled = enabled
    case let .second(retryConfig):
      isEnabled = true
      retryConfig.maxRetryDurationMs.map { budget.recoveryMs = Int($0) }
      retryConfig.firstConnectMaxRetryDurationMs.map { budget.firstConnectMs = Int($0) }
      pacer.maxRetries = retryConfig.maxRetries.map { Int($0) }
    }
    logger.debug("Retry enabled: \(self.isEnabled), max retries \(String(describing: self.pacer.maxRetries))")
    armDeadline()
  }

  // MARK: - Inputs

  /// A new Load: a track change, or a restart from the terminal error. Drops the retry in flight.
  func loadStarted() {
    cancel()
    lastFailure = nil
    replacedConnectionCounts = false
    budget.loadStarted(atMs: clock.nowMs)
    pacer.reset()
  }

  /// A connect going out: the Load's first, a reconnect, or a reload by the Player, which ends the
  /// connection it replaces. A replaced connection counts only if it delivered audio: one cut off
  /// while connecting says nothing about the source.
  func connectionStarted() {
    let now = clock.nowMs
    if let startedAtMs = connectionStartedAtMs, replacedConnectionCounts, connectionDeliveredAudio, !connectionEndReported {
      pacer.connectionEnded(deliveredAudio: true, startedAtMs: startedAtMs, atMs: now)
    }
    connectionStartedAtMs = now
    replacedConnectionCounts = true
    connectionDeliveredAudio = false
    connectionEndReported = false
    deadlineArmed = true
    armDeadline()
  }

  func playIntentChanged(_ up: Bool) {
    guard up != playIntent else { return }
    playIntent = up
    if up {
      lastFailure = nil
      // The pacer starts over: a reload now does not count the connection it replaces.
      replacedConnectionCounts = false
    }
    budget.playIntentChanged(up, atMs: clock.nowMs)
    pacer.playIntentChanged(up)
    armDeadline()
  }

  func onlineChanged(_ online: Bool) {
    budget.onlineChanged(online)
    pacer.onlineChanged(online)
    armDeadline()
  }

  /// Audio clears the failure the deadline would report: a silence after it that ends with no
  /// failure is a stall.
  func playbackBecameAudible() {
    connectionDeliveredAudio = true
    lastFailure = nil
    budget.audioStarted(atMs: clock.nowMs)
    armDeadline()
  }

  func playbackStoppedBeingAudible() {
    budget.audioStopped(atMs: clock.nowMs)
    armDeadline()
  }

  // MARK: - Failures

  /// Reports a non-retryable failure through `onError` at once, and one with no play intent
  /// through `onFailedWithoutIntent`; otherwise reports it through `onRetryingError`, replaces any
  /// retry in flight, and calls `onError` only if that retry gives up.
  func handleError(_ error: Error?, context: PlaybackErrorContext, httpStatusCode: Int? = nil) {
    if let error {
      let nsError = error as NSError
      logger.error("[\(String(describing: context))] failure: domain=\(nsError.domain), code=\(nsError.code), localizedDescription=\(error.localizedDescription)")
    } else {
      logger.error("[\(String(describing: context))] failure with nil error")
    }

    handle(PlaybackFailure.classify(error, httpStatusCode: httpStatusCode, fallback: context.fallbackError), context: context)
  }

  /// A failure the Player judges from what playback did, with no error to classify: retryable,
  /// and reported as the context's fallback error.
  func handleFailure(_ context: PlaybackErrorContext) {
    logger.error("[\(String(describing: context))] failure")
    handle(.init(error: context.fallbackError, retryable: true), context: context)
  }

  private func handle(_ failure: PlaybackFailure.Classification, context: PlaybackErrorContext) {
    stopStallWatch()
    guard isEnabled, failure.retryable else {
      let reason = isEnabled ? "Error not retryable" : "Retry disabled"
      logger.warning("\(reason), surfacing \(String(describing: failure.error))")
      return giveUp(failure.error)
    }
    guard playIntent else {
      logger.info("No play intent: \(String(describing: failure.error)) is left to the next play")
      onFailedWithoutIntent?()
      return
    }
    lastFailure = failure.error
    let now = clock.nowMs
    if !connectionEndReported {
      connectionEndReported = true
      // The Player can stay .playing on a failure, on buffered audio that is about to run out.
      budget.audioStopped(atMs: now)
      budget.connectionEnded(atMs: now)
      pacer.connectionEnded(deliveredAudio: connectionDeliveredAudio, startedAtMs: connectionStartedAtMs ?? now, atMs: now)
    }
    onRetryingError?(failure.error)
    armDeadline()
    pendingTask?.cancel()
    pendingTask = Task { [weak self] in
      await self?.reconnect(startFromCurrentTime: context.startFromCurrentTime)
    }
  }

  /// Asks the pacer again every half second at most, so a change in connectivity or a passed
  /// deadline is seen without waiting out the whole wait.
  private func reconnect(startFromCurrentTime: Bool) async {
    while !Task.isCancelled {
      if let deadline = budget.deadlineMs, deadline <= clock.nowMs {
        return giveUp(lastFailure ?? .playbackStalled)
      }
      let waitMs: Int
      switch pacer.wait(atMs: clock.nowMs) {
      case .giveUp:
        logger.info("Max retries (\(self.pacer.maxRetries ?? 0)) exceeded, giving up")
        return giveUp(lastFailure ?? .playbackStalled)
      case .untilOnline:
        waitMs = 500
      case let .after(ms):
        waitMs = min(ms, 500)
      }
      if waitMs == 0 { break }
      // Cancelled by a track change or stop: stay silent, or the new track or the stop would
      // flip to .error. The canceller owns `pendingTask`.
      guard await (try? sleep(waitMs)) != nil else { return }
    }
    if Task.isCancelled { return }
    pendingTask = nil
    logger.info("Reconnecting")
    onRetry?(startFromCurrentTime)
  }

  private func giveUp(_ error: TrackPlayerError.PlaybackError) {
    cancel()
    onError?(error)
  }

  /// Drops the retry in flight, the stall watch and the deadline until the next connection starts.
  func cancel() {
    pendingTask?.cancel()
    pendingTask = nil
    stopStallWatch()
    deadlineTask?.cancel()
    deadlineTask = nil
    deadlineArmed = false
  }

  // MARK: - Deadline

  private func armDeadline() {
    deadlineTask?.cancel()
    deadlineTask = nil
    guard isEnabled, deadlineArmed, let deadline = budget.deadlineMs else { return }
    deadlineTask = Task { [weak self, clock] in
      let remaining = deadline - clock.nowMs
      if remaining > 0 { guard await (try? clock.sleep(ms: remaining)) != nil else { return } }
      guard let self, !Task.isCancelled else { return }
      logger.info("Silent past the deadline, giving up")
      giveUp(lastFailure ?? .playbackStalled)
    }
  }

  // MARK: - Stalls

  /// Starts the stall watch: a `stallTimeout` in which `bufferedPosition` does not advance makes
  /// the stall a `.stall` failure, provided the play intent holds and no retry is pending; one in
  /// which it advances starts another. Any failure ends it.
  func startStallWatch() {
    stallTask?.cancel()
    let buffered = bufferedPosition()
    stallTask = Task { [weak self, stallTimeout] in
      try? await Task.sleep(for: stallTimeout)
      guard let self, !Task.isCancelled else { return }
      stallTask = nil
      if bufferedPosition() > buffered { return startStallWatch() }
      guard pendingTask == nil, playIntent else { return }
      handleFailure(.stall)
    }
  }

  func stopStallWatch() {
    stallTask?.cancel()
    stallTask = nil
  }
}
