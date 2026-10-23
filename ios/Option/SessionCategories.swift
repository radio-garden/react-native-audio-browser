import AVFoundation

extension IOSCategory {
  var avCategory: AVAudioSession.Category {
    switch self {
    case .playback: .playback
    case .playandrecord: .playAndRecord
    case .multiroute: .multiRoute
    case .ambient: .ambient
    case .soloambient: .soloAmbient
    case .record: .record
    }
  }
}

extension IOSCategoryMode {
  var avMode: AVAudioSession.Mode {
    switch self {
    case .default: .default
    case .gamechat: .gameChat
    case .measurement: .measurement
    case .movieplayback: .moviePlayback
    case .spokenaudio: .spokenAudio
    case .videochat: .videoChat
    case .videorecording: .videoRecording
    case .voicechat: .voiceChat
    case .voiceprompt: .voicePrompt
    }
  }
}

extension IOSCategoryPolicy {
  var avPolicy: AVAudioSession.RouteSharingPolicy {
    switch self {
    case .default: .default
    case .longformaudio: .longFormAudio
    case .longformvideo: .longFormVideo
    }
  }
}

extension IOSCategoryOptions {
  var avOptions: AVAudioSession.CategoryOptions {
    switch self {
    case .mixwithothers: .mixWithOthers
    case .duckothers: .duckOthers
    case .interruptspokenaudioandmixwithothers: .interruptSpokenAudioAndMixWithOthers
    case .allowbluetooth: .allowBluetoothHFP
    case .allowbluetootha2dp: .allowBluetoothA2DP
    case .allowairplay: .allowAirPlay
    case .defaulttospeaker: .defaultToSpeaker
    }
  }
}

extension NativeIOSSetupOptions {
  /// The `AVAudioSession` configuration these options ask for; an absent field takes the
  /// playback default.
  func resolveAudioSessionConfig() -> (
    category: AVAudioSession.Category,
    mode: AVAudioSession.Mode,
    policy: AVAudioSession.RouteSharingPolicy,
    options: AVAudioSession.CategoryOptions,
  ) {
    (
      category?.avCategory ?? .playback,
      categoryMode?.avMode ?? .default,
      categoryPolicy?.avPolicy ?? .default,
      AVAudioSession.CategoryOptions((categoryOptions ?? []).map(\.avOptions)),
    )
  }
}
