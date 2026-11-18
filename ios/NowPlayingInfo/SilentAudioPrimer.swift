import AVFoundation
import Foundation
import os.log

/// Plays a fraction of a second of silence (zero samples) through the app's audio session.
///
/// iOS puts an app on the now-playing surfaces (lock screen, Control Center, CarPlay) only once
/// its audio session renders output; published metadata alone never surfaces it, and
/// `MPNowPlayingInfoCenter.playbackState` is dropped without a private entitlement
/// (`com.apple.mediaremote.set-playback-state`). When the first load fails, nothing renders, so
/// the already-published info and transport controls never show. This burst wins the election
/// (docs/adr/0005).
@MainActor
final class SilentAudioPrimer {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "SilentAudioPrimer")

  private static let duration: TimeInterval = 0.3

  // Held until playback ends; a released AVAudioPlayer stops rendering.
  private var player: AVAudioPlayer?

  func prime() {
    guard player == nil else { return }
    guard let wav = Self.silentWav else { return }
    do {
      let player = try AVAudioPlayer(data: wav)
      self.player = player
      logger.notice("Priming now-playing election with \(Self.duration)s of silence")
      player.play()
      DispatchQueue.main.asyncAfter(deadline: .now() + Self.duration + 0.2) { [weak self] in
        self?.player = nil
      }
    } catch {
      logger.error("Failed to create silent primer player: \(error.localizedDescription)")
    }
  }

  // Mono 16-bit PCM WAV; 8 kHz keeps it a few KB, and zeros need no fidelity.
  private static let silentWav: Data? = makeSilentWav()

  private static func makeSilentWav() -> Data? {
    let sampleRate = 8000
    let sampleCount = Int(Double(sampleRate) * duration)
    let dataSize = sampleCount * 2 // 16-bit mono
    var data = Data(capacity: 44 + dataSize)

    func append(_ string: String) { data.append(contentsOf: Array(string.utf8)) }
    func append32(_ value: Int) { withUnsafeBytes(of: UInt32(value).littleEndian) { data.append(contentsOf: $0) } }
    func append16(_ value: Int) { withUnsafeBytes(of: UInt16(value).littleEndian) { data.append(contentsOf: $0) } }

    append("RIFF")
    append32(36 + dataSize)
    append("WAVE")
    append("fmt ")
    append32(16) // fmt chunk size
    append16(1) // linear PCM
    append16(1) // mono
    append32(sampleRate)
    append32(sampleRate * 2) // byte rate
    append16(2) // block align
    append16(16) // bits per sample
    append("data")
    append32(dataSize)
    data.append(contentsOf: [UInt8](repeating: 0, count: dataSize))
    return data
  }
}
