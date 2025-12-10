import AVFoundation
import Foundation

public enum PitchAlgorithm: String {
  case linear
  case music
  case voice

  /// The corresponding AVAudioTimePitchAlgorithm for use with AVPlayer
  public var avAlgorithm: AVAudioTimePitchAlgorithm {
    switch self {
    case .linear: .varispeed
    case .music: .spectral
    case .voice: .timeDomain
    }
  }
}
