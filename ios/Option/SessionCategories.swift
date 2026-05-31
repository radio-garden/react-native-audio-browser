import AVFoundation
import Foundation
import MediaPlayer

enum SessionCategory: String {
  case playAndRecord, multiRoute, playback, ambient, soloAmbient

  func mapConfigToAVAudioSessionCategory() -> AVAudioSession.Category {
    switch self {
    case .playAndRecord:
      .playAndRecord
    case .multiRoute:
      .multiRoute
    case .playback:
      .playback
    case .ambient:
      .ambient
    case .soloAmbient:
      .soloAmbient
    }
  }
}

enum SessionCategoryPolicy: String {
  case `default`, longFormAudio, longFormVideo

  func toRouteSharingPolicy() -> AVAudioSession.RouteSharingPolicy {
    switch self {
    case .default:
      .default
    case .longFormAudio:
      .longFormAudio
    case .longFormVideo:
      .longFormVideo
    }
  }
}

enum SessionCategoryMode: String {
  case `default`, gameChat, measurement, moviePlayback, spokenAudio, videoChat, videoRecording,
       voiceChat, voicePrompt

  func mapConfigToAVAudioSessionCategoryMode() -> AVAudioSession.Mode {
    switch self {
    case .default:
      .default
    case .gameChat:
      .gameChat
    case .measurement:
      .measurement
    case .moviePlayback:
      .moviePlayback
    case .spokenAudio:
      .spokenAudio
    case .videoChat:
      .videoChat
    case .videoRecording:
      .videoRecording
    case .voiceChat:
      .voiceChat
    case .voicePrompt:
      .voicePrompt
    }
  }
}

enum SessionCategoryOptions: String {
  case mixWithOthers, duckOthers, interruptSpokenAudioAndMixWithOthers, allowBluetooth,
       allowBluetoothA2DP,
       allowAirPlay,
       defaultToSpeaker

  func mapConfigToAVAudioSessionCategoryOptions() -> AVAudioSession.CategoryOptions? {
    switch self {
    case .mixWithOthers:
      .mixWithOthers
    case .duckOthers:
      .duckOthers
    case .interruptSpokenAudioAndMixWithOthers:
      .interruptSpokenAudioAndMixWithOthers
    case .allowBluetooth:
      .allowBluetoothHFP
    case .allowBluetoothA2DP:
      .allowBluetoothA2DP
    case .allowAirPlay:
      .allowAirPlay
    case .defaultToSpeaker:
      .defaultToSpeaker
    }
  }
}
