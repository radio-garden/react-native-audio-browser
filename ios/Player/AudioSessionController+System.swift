import AVFoundation

extension AudioSessionController {
  convenience init() {
    self.init { active in
      if active {
        try AVAudioSession.sharedInstance().setActive(true)
      } else {
        try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
      }
    }
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
        MainActor.assumeIsolated { self?.mediaServicesWereReset() }
      },
    ]
    volumeObservation = AVAudioSession.sharedInstance().observe(\.outputVolume, options: [.new]) {
      [weak self] _, change in
      guard let volume = change.newValue else { return }
      Task { @MainActor in self?.volumeChanged.emit(Double(volume)) }
    }
  }

  /// Sets the session category from the iOS setup options, without activating it. Returns once it
  /// is set.
  func configure(_ options: NativeIOSSetupOptions?) async {
    let config = options?.resolveAudioSessionConfig() ?? (.playback, .default, .default, [])
    await configure {
      try AVAudioSession.sharedInstance().setCategory(
        config.category, mode: config.mode, policy: config.policy, options: config.options,
      )
    }
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
      interruptionBegan()
    case .ended:
      interruptionEnded(
        shouldResume: options.map { AVAudioSession.InterruptionOptions(rawValue: $0).contains(.shouldResume) } ?? false,
      )
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
