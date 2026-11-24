import AVFoundation
import NitroModules
import os

/// What `AudioSessionController` tells the player about the audio session.
@MainActor
protocol AudioSessionPlayer: AnyObject {
  var canReleaseAudioSession: Bool { get }
  var willResumeAfterInterruption: Bool { get }
  func handleInterruptionBegan()
  func handleInterruptionEnded(shouldResume: Bool)
  func handleRouteDisconnected()
  func mediaServicesWereReset()
}

extension Player: AudioSessionPlayer {}

/// Owns the app's `AVAudioSession`: category, activation and release, and the interruption,
/// route-change, media-services-reset and volume observers.
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
  private var configuration: (
    category: AVAudioSession.Category,
    mode: AVAudioSession.Mode,
    policy: AVAudioSession.RouteSharingPolicy,
    options: AVAudioSession.CategoryOptions,
  )?

  /// Shared, as the session is: after a JS reload the old instance's release must not fire
  /// under the new instance's playback.
  private static var releaseWork: DispatchWorkItem?

  /// Set when a JS reload replaces this controller's instance; read on `activationQueue`, so a
  /// category queued before the reload never lands after the new instance's.
  private let retired = OSAllocatedUnfairLock(initialState: false)

  /// `setActive` blocks until the system has changed the session, long enough to stall the main
  /// thread. Serial and shared, like the session, so a release and a quick re-activation keep their
  /// order, also across the controllers of a JS reload.
  private static let activationQueue = DispatchQueue(label: "com.audiobrowser.audio-session", qos: .userInitiated)

  // Touched only by `init` and `deinit`.
  private nonisolated(unsafe) var observers: [NSObjectProtocol] = []
  private var volumeObservation: NSKeyValueObservation?

  private var session: AVAudioSession {
    .sharedInstance()
  }

  init() {
    let center = NotificationCenter.default
    observers = [
      center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) {
        [weak self] notification in
        let info = notification.userInfo
        let type = info?[AVAudioSessionInterruptionTypeKey] as? UInt
        let reason = info?[AVAudioSessionInterruptionReasonKey] as? UInt
        let options = info?[AVAudioSessionInterruptionOptionKey] as? UInt
        MainActor.assumeIsolated { self?.handleInterruption(type: type, reason: reason, options: options) }
      },
      center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) {
        [weak self] notification in
        let reason = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
        MainActor.assumeIsolated { self?.handleRouteChange(reason: reason) }
      },
      center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) {
        [weak self] _ in
        MainActor.assumeIsolated {
          // The reset cleared the category and invalidated every player and session handle.
          self?.applyCategory { [weak self] in self?.player?.mediaServicesWereReset() }
        }
      },
    ]
    volumeObservation = session.observe(\.outputVolume, options: [.new]) { [weak self] _, change in
      guard let volume = change.newValue else { return }
      Task { @MainActor in self?.volumeChanged.emit(Double(volume)) }
    }
  }

  deinit {
    for observer in observers {
      NotificationCenter.default.removeObserver(observer)
    }
  }

  /// Sets the session category from the iOS setup options, without activating it. Returns once it
  /// is set.
  func configure(_ options: NativeIOSSetupOptions?) async {
    configuration = options?.resolveAudioSessionConfig() ?? (.playback, .default, .default, [])
    await withCheckedContinuation { continuation in applyCategory { continuation.resume() } }
  }

  /// Activates the session and cancels a pending release. `then` runs on the main actor once the
  /// activation has run, whether or not it succeeded.
  func activate(then: (@MainActor @Sendable () -> Void)? = nil) {
    cancelRelease()
    Self.activationQueue.async {
      try? AVAudioSession.sharedInstance().setActive(true)
      if let then { Task { @MainActor in then() } }
    }
  }

  /// Releases the session after 3 s unless playback resumes, so other apps' audio can come back.
  /// Debounced: iOS dislikes deactivating while audio trails off, and a quick stop and play
  /// should not thrash it.
  func scheduleRelease() {
    Self.releaseWork?.cancel()
    // Not through `self`: a JS reload drops this controller within the 3 s, and the release it
    // owes must still run, unless the new player has restored a track in the meantime.
    let release = DispatchWorkItem {
      MainActor.assumeIsolated {
        guard Self.currentPlayer?.canReleaseAudioSession ?? true else { return }
        Self.releaseWork = nil
        Self.activationQueue.async {
          try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
      }
    }
    Self.releaseWork = release
    DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: release)
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

  nonisolated static var volume: Double {
    Double(AVAudioSession.sharedInstance().outputVolume)
  }

  nonisolated static var currentOutput: Output? {
    guard let output = AVAudioSession.sharedInstance().currentRoute.outputs.first else { return nil }
    let (type, external): (OutputType, Bool) = switch output.portType {
    case .builtInSpeaker: (.speaker, false)
    case .builtInReceiver: (.receiver, false)
    case .airPlay: (.airplay, true)
    case .bluetoothA2DP, .bluetoothHFP, .bluetoothLE: (.bluetooth, true)
    case .headphones: (.headphones, true)
    case .carAudio: (.car, true)
    case .HDMI: (.hdmi, true)
    case .usbAudio: (.usb, true)
    default: (.other, true)
    }
    return Output(type: type, name: output.portName, external: external)
  }

  // MARK: - Private

  /// On the activation queue: `setCategory` also waits on the audio server, and the serial queue
  /// keeps it ahead of the activation that follows it. Playback starts on main, outside the queue,
  /// so it must wait for `then`, which runs on the main actor once the category is set.
  private func applyCategory(then: @escaping @MainActor @Sendable () -> Void) {
    guard let configuration else { return then() }
    let retired = retired
    Self.activationQueue.async {
      if !retired.withLock({ $0 }) {
        try? AVAudioSession.sharedInstance().setCategory(
          configuration.category, mode: configuration.mode, policy: configuration.policy, options: configuration.options,
        )
      }
      Task { @MainActor in then() }
    }
  }

  private func handleInterruption(type: UInt?, reason: UInt?, options: UInt?) {
    guard let type = type.flatMap(AVAudioSession.InterruptionType.init(rawValue:)) else { return }
    switch type {
    case .began:
      // iOS 17+ also begins one, never ended, when an output route disappears; the route change
      // handles that.
      if #available(iOS 17.0, *),
         let reason,
         AVAudioSession.InterruptionReason(rawValue: reason) == .routeDisconnected
      {
        return
      }
      player?.handleInterruptionBegan()
    case .ended:
      let shouldResume = options.map { AVAudioSession.InterruptionOptions(rawValue: $0).contains(.shouldResume) } ?? false
      // Reactivate only when a resume follows, and resume once active: activating while staying
      // paused would hold a non-mixable session with nothing to release it.
      if shouldResume, player?.willResumeAfterInterruption == true {
        activate { [weak self] in self?.player?.handleInterruptionEnded(shouldResume: true) }
      } else {
        player?.handleInterruptionEnded(shouldResume: shouldResume)
      }
    @unknown default:
      break
    }
  }

  private func handleRouteChange(reason: UInt?) {
    // The output disappeared (headphones unplugged, a speaker off): pause rather than carry on
    // from the built-in speaker.
    if let reason, AVAudioSession.RouteChangeReason(rawValue: reason) == .oldDeviceUnavailable {
      player?.handleRouteDisconnected()
    }
    if let output = Self.currentOutput { outputChanged.emit(output) }
  }
}
