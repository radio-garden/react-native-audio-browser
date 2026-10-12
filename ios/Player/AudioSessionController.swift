import AVFoundation
import NitroModules

/// What `AudioSessionController` tells the player about the audio session.
@MainActor
protocol AudioSessionPlayer: AnyObject {
  var playWhenReady: Bool { get }
  var willResumeAfterInterruption: Bool { get }
  func handleInterruptionBegan()
  func handleInterruptionEnded(shouldResume: Bool)
  func handleRouteDisconnected()
  func handleMediaServicesReset()
}

/// Owns the app's `AVAudioSession`: its category, activation and release, and the interruption,
/// route-change, media-services-reset and volume observers, installed once at init.
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
  private var configuration: (
    category: AVAudioSession.Category,
    mode: AVAudioSession.Mode,
    policy: AVAudioSession.RouteSharingPolicy,
    options: AVAudioSession.CategoryOptions,
  )?

  private var releaseWork: DispatchWorkItem?

  /// Touched only by `init` and `deinit`.
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
          self?.applyCategory()
          self?.player?.handleMediaServicesReset()
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

  /// Sets the session category from the iOS setup options, without activating it.
  func configure(_ options: NativeIOSSetupOptions?) {
    configuration = options?.resolveAudioSessionConfig() ?? (.playback, .default, .default, [])
    applyCategory()
  }

  /// Activates the session for output and cancels a pending release.
  func activate() {
    try? session.setActive(true)
    cancelRelease()
  }

  /// Releases the session after 3 s unless playback resumes, so other apps' audio can come back.
  /// Debounced: iOS dislikes deactivating while audio trails off, and a quick pause → resume
  /// should not thrash it.
  func scheduleRelease() {
    releaseWork?.cancel()
    let release = DispatchWorkItem { [weak self] in
      guard let self, player?.playWhenReady == false else { return }
      releaseWork = nil
      try? session.setActive(false, options: .notifyOthersOnDeactivation)
    }
    releaseWork = release
    DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: release)
  }

  func cancelRelease() {
    releaseWork?.cancel()
    releaseWork = nil
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

  private func applyCategory() {
    guard let configuration else { return }
    try? session.setCategory(
      configuration.category, mode: configuration.mode, policy: configuration.policy, options: configuration.options,
    )
  }

  private func handleInterruption(type: UInt?, reason: UInt?, options: UInt?) {
    guard let type = type.flatMap(AVAudioSession.InterruptionType.init(rawValue:)) else { return }
    switch type {
    case .began:
      // iOS 17+ also begins one, never ended, when an output route disappears; the route
      // change handles that.
      if #available(iOS 17.0, *),
         let reason,
         AVAudioSession.InterruptionReason(rawValue: reason) == .routeDisconnected
      {
        return
      }
      player?.handleInterruptionBegan()
    case .ended:
      let shouldResume = options.map { AVAudioSession.InterruptionOptions(rawValue: $0).contains(.shouldResume) } ?? false
      // Reactivate only when a resume follows: activating while staying paused would hold a
      // non-mixable session with nothing to release it.
      if shouldResume, player?.willResumeAfterInterruption == true {
        try? session.setActive(true)
      }
      player?.handleInterruptionEnded(shouldResume: shouldResume)
    @unknown default:
      break
    }
  }

  private func handleRouteChange(reason: UInt?) {
    // The output disappeared (headphones unplugged, a speaker off): pause, as every media app
    // does, rather than carry on from the built-in speaker.
    if let reason, AVAudioSession.RouteChangeReason(rawValue: reason) == .oldDeviceUnavailable {
      player?.handleRouteDisconnected()
    }
    if let output = Self.currentOutput { outputChanged.emit(output) }
  }
}
