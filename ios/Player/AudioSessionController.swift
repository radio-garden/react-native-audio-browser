import Foundation
import os

/// What `AudioSessionController` tells the player about the audio session.
@MainActor
protocol AudioSessionPlayer: AnyObject {
  var canReleaseAudioSession: Bool { get }
  var willResumeAfterInterruption: Bool { get }
  func handleInterruptionBegan()
  func handleAudioSessionActivationFailed()
  func handleInterruptionEnded(shouldResume: Bool)
  func handleRouteDisconnected()
  func mediaServicesWereReset()
}

extension Player: AudioSessionPlayer {}

/// Owns the app's `AVAudioSession`: category, activation and release, and the interruption,
/// route-change, media-services-reset and volume observers. The `AVAudioSession` calls and the
/// observers are in `AudioSessionController+System.swift`, outside the SwiftPM test target.
///
/// One per process, `shared`, as the session is: across a JS reload it outlives both instances,
/// and serves whichever player the latest setup gave it.
///
/// The session is configured at setup but activated only when playback produces output:
/// activating a non-mixable session interrupts other apps' audio, which must not happen at
/// launch.
@MainActor
final class AudioSessionController {
  weak var player: AudioSessionPlayer?

  let outputChanged = Emitter<Output>()
  let volumeChanged = Emitter<Double>()

  /// Nil until `configure`: before setup the category is the host app's, not ours to restore.
  private var setCategory: (@Sendable () throws -> Void)?

  /// Runs on `activationQueue`; `false` deactivates, notifying other apps.
  private let setActive: @Sendable (Bool) throws -> Void
  private let releaseDelay: DispatchTimeInterval

  private var releaseWork: DispatchWorkItem?
  private let logger = Logger(subsystem: "com.audiobrowser", category: "AudioSession")
  /// Bumped by each activation and each interruption: a refusal older than either is stale.
  private var generation = 0
  /// Bumped by each interruption: a resume older than one is stale.
  private var interruptions = 0

  /// `setActive` blocks until the system has changed the session, long enough to stall the main
  /// thread. Serial, so a release and a quick re-activation keep their order.
  let activationQueue = DispatchQueue(label: "com.audiobrowser.audio-session", qos: .userInitiated)

  init(setActive: @escaping @Sendable (Bool) throws -> Void, releaseDelay: DispatchTimeInterval = .seconds(3)) {
    self.setActive = setActive
    self.releaseDelay = releaseDelay
  }

  /// Sets the session category with `setCategory`, without activating it, and again after a
  /// media-services reset. Returns once it is set.
  func configure(setCategory: @escaping @Sendable () throws -> Void) async {
    self.setCategory = setCategory
    await withCheckedContinuation { continuation in applyCategory { continuation.resume() } }
  }

  /// Activates the session and cancels a pending release. `then` runs on the main actor once the
  /// session is active; a refusal is logged and reported to the player instead.
  func activate(then: (@MainActor @Sendable () -> Void)? = nil) {
    cancelRelease()
    generation += 1
    let generation = generation
    let setActive = setActive
    activationQueue.async {
      do {
        try setActive(true)
        if let then { Task { @MainActor in then() } }
      } catch {
        let message = error.localizedDescription
        Task { @MainActor [weak self] in
          guard let self else { return }
          logger.error("The audio session did not activate: \(message, privacy: .public)")
          guard generation == self.generation else { return }
          player?.handleAudioSessionActivationFailed()
        }
      }
    }
  }

  /// Releases the session after 3 s unless playback resumes, so other apps' audio can come back.
  /// Debounced: iOS dislikes deactivating while audio trails off, and a quick stop and play
  /// should not thrash it.
  func scheduleRelease() {
    releaseWork?.cancel()
    // Judged when it fires: a JS reload within the 3 s may have handed the session to a new
    // player, or to none.
    let release = DispatchWorkItem { [weak self] in
      MainActor.assumeIsolated {
        guard let self, self.player?.canReleaseAudioSession ?? true else { return }
        self.releaseWork = nil
        self.activationQueue.async { [setActive = self.setActive] in try? setActive(false) }
      }
    }
    releaseWork = release
    DispatchQueue.main.asyncAfter(deadline: .now() + releaseDelay, execute: release)
  }

  /// Stops serving `player`, unless a later setup has already replaced it: its JS instance is
  /// gone, and an interruption's end must not resume it.
  func detach(_ player: AudioSessionPlayer?) {
    if self.player === player { self.player = nil }
  }

  func cancelRelease() {
    releaseWork?.cancel()
    releaseWork = nil
  }

  func interruptionBegan() {
    generation += 1
    interruptions += 1
    player?.handleInterruptionBegan()
  }

  func interruptionEnded(shouldResume: Bool) {
    // Reactivate only when a resume follows, and resume once active: activating while staying
    // paused would hold a non-mixable session with nothing to release it.
    if shouldResume, player?.willResumeAfterInterruption == true {
      let interruptions = interruptions
      activate { [weak self] in
        guard let self else { return }
        // Another interruption began while the session activated: its own end resumes.
        if interruptions == self.interruptions { player?.handleInterruptionEnded(shouldResume: true) }
        // A JS reload, or a resume dropped meanwhile, may have left nothing to hold the session.
        scheduleRelease()
      }
    } else {
      player?.handleInterruptionEnded(shouldResume: shouldResume)
    }
  }

  /// The reset cleared the category and invalidated every player and session handle.
  func mediaServicesWereReset() {
    applyCategory { [weak self] in self?.player?.mediaServicesWereReset() }
  }

  // MARK: - Private

  /// On the activation queue: `setCategory` also waits on the audio server, and the serial queue
  /// keeps it ahead of the activation that follows it. Playback starts on main, outside the queue,
  /// so it must wait for `then`, which runs on the main actor once the category is set.
  private func applyCategory(then: @escaping @MainActor @Sendable () -> Void) {
    guard let setCategory else { return then() }
    activationQueue.async {
      try? setCategory()
      Task { @MainActor in then() }
    }
  }
}
