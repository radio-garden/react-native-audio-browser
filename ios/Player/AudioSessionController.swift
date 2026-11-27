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
/// The session is configured at setup but activated only when playback produces output:
/// activating a non-mixable session interrupts other apps' audio, which must not happen at
/// launch.
@MainActor
final class AudioSessionController {
  weak var player: AudioSessionPlayer? {
    didSet { if !isRetired { Self.currentPlayer = player } }
  }

  /// The newest controller's player: after a JS reload, the one whose playback a release must be
  /// judged against.
  private weak static var currentPlayer: AudioSessionPlayer?

  let outputChanged = Emitter<Output>()
  let volumeChanged = Emitter<Double>()

  /// Nil until `configure`: before setup the category is the host app's, not ours to restore.
  private var setCategory: (@Sendable () throws -> Void)?

  /// Runs on `activationQueue`; `false` deactivates, notifying other apps.
  private let setActive: @Sendable (Bool) throws -> Void
  private let releaseDelay: DispatchTimeInterval

  /// Shared, as the session is: after a JS reload the old instance's release must not fire
  /// under the new instance's playback.
  private static var releaseWork: DispatchWorkItem?
  private let logger = Logger(subsystem: "com.audiobrowser", category: "AudioSession")
  /// Bumped by each activation and by each interruption of the live controller: a refusal older
  /// than either is stale, as is one on a retired controller.
  private static var generation = 0

  /// Set when a JS reload replaces this controller's instance; read on `activationQueue`, so a
  /// category queued before the reload never lands after the new instance's.
  private let retired = OSAllocatedUnfairLock(initialState: false)

  /// `setActive` blocks until the system has changed the session, long enough to stall the main
  /// thread. Serial and shared, like the session, so a release and a quick re-activation keep their
  /// order, also across the controllers of a JS reload.
  static let activationQueue = DispatchQueue(label: "com.audiobrowser.audio-session", qos: .userInitiated)

  // Touched only by `init` and `deinit`.
  nonisolated(unsafe) var observers: [NSObjectProtocol] = []
  var volumeObservation: NSKeyValueObservation?

  init(setActive: @escaping @Sendable (Bool) throws -> Void, releaseDelay: DispatchTimeInterval = .seconds(3)) {
    self.setActive = setActive
    self.releaseDelay = releaseDelay
  }

  deinit {
    for observer in observers {
      NotificationCenter.default.removeObserver(observer)
    }
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
    Self.generation += 1
    let generation = Self.generation
    let setActive = setActive
    Self.activationQueue.async {
      do {
        try setActive(true)
        if let then { Task { @MainActor in then() } }
      } catch {
        let message = error.localizedDescription
        Task { @MainActor [weak self] in
          guard let self else { return }
          logger.error("The audio session did not activate: \(message, privacy: .public)")
          guard generation == Self.generation, !isRetired else { return }
          player?.handleAudioSessionActivationFailed()
        }
      }
    }
  }

  /// Releases the session after 3 s unless playback resumes, so other apps' audio can come back.
  /// Debounced: iOS dislikes deactivating while audio trails off, and a quick stop and play
  /// should not thrash it.
  func scheduleRelease() {
    Self.releaseWork?.cancel()
    let setActive = setActive
    // Not through `self`: a JS reload drops this controller within the 3 s, and the release it
    // owes must still run, unless the new player has restored a track in the meantime.
    let release = DispatchWorkItem {
      MainActor.assumeIsolated {
        guard Self.currentPlayer?.canReleaseAudioSession ?? true else { return }
        Self.releaseWork = nil
        Self.activationQueue.async { try? setActive(false) }
      }
    }
    Self.releaseWork = release
    DispatchQueue.main.asyncAfter(deadline: .now() + releaseDelay, execute: release)
  }

  /// Stops this controller setting the category: a JS reload's new instance owns the session now.
  func retire() {
    retired.withLock { $0 = true }
  }

  private var isRetired: Bool {
    retired.withLock { $0 }
  }

  func cancelRelease() {
    Self.releaseWork?.cancel()
    Self.releaseWork = nil
  }

  func interruptionBegan() {
    if !isRetired { Self.generation += 1 }
    player?.handleInterruptionBegan()
  }

  func interruptionEnded(shouldResume: Bool) {
    // Reactivate only when a resume follows, and resume once active: activating while staying
    // paused would hold a non-mixable session with nothing to release it.
    if shouldResume, player?.willResumeAfterInterruption == true {
      activate { [weak self] in self?.player?.handleInterruptionEnded(shouldResume: true) }
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
    let retired = retired
    Self.activationQueue.async {
      if !retired.withLock({ $0 }) { try? setCategory() }
      Task { @MainActor in then() }
    }
  }
}
