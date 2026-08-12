import AVFoundation
import Foundation
import os.log

/// Renders a fraction of a second of generated silence through the app's audio session.
///
/// iOS elects an app onto the system now-playing surfaces (lock screen, Control
/// Center, the CarPlay now-playing template) only once its audio session actually
/// renders output. Published metadata alone never surfaces — and the explicit
/// `MPNowPlayingInfoCenter.playbackState` write is dropped on iOS without a private
/// entitlement (`com.apple.mediaremote.set-playback-state`), so there is no
/// declarative way in. When the very first load of a session fails, no audio ever
/// renders and the failure line + transport controls have nowhere to appear.
/// Rendering one burst of silence satisfies the election rule; the session's
/// already-published info (station + failure line) then displays.
///
/// The samples are zeros: inaudible by construction, no volume tricks involved.
@MainActor
final class SilentAudioPrimer {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "SilentAudioPrimer")

  private static let duration: TimeInterval = 0.3

  /// Held while playing so the player isn't deallocated mid-render.
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

  /// A minimal mono 16-bit PCM WAV of silence. 8 kHz keeps it a few KB — the
  /// content is zeros, so fidelity is irrelevant.
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
    append32(16) // PCM fmt chunk size
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
