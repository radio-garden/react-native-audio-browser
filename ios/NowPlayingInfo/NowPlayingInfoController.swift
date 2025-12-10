import Foundation
import MediaPlayer
import os.log

/// Thread-safe controller for managing MPNowPlayingInfoCenter.
/// Uses a concurrent queue with barriers to synchronize access to the info dictionary.
class NowPlayingInfoController {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "NowPlayingInfoController")
  private var infoQueue = DispatchQueue(
    label: "NowPlayingInfoController.infoQueue",
    attributes: .concurrent,
  )

  private(set) var infoCenter: NowPlayingInfoCenter
  private var _info: [String: Any] = [:]

  /// Thread-safe access to the current Now Playing info dictionary
  var info: [String: Any] {
    infoQueue.sync { _info }
  }

  required init() {
    infoCenter = MPNowPlayingInfoCenter.default()
  }

  required init(infoCenter: NowPlayingInfoCenter) {
    self.infoCenter = infoCenter
  }

  /// Sets key-values and immediately updates the Now Playing Info Center
  func set(keyValues: [NowPlayingInfoKeyValue]) {
    infoQueue.async(flags: .barrier) { [weak self] in
      guard let self else { return }
      for keyValue in keyValues {
        _info[keyValue.key] = keyValue.value
      }
      performUpdate()
    }
  }

  /// Sets key-values without updating the Now Playing Info Center.
  /// Useful for batching multiple updates - call update() when ready to commit.
  func setWithoutUpdate(keyValues: [NowPlayingInfoKeyValue]) {
    infoQueue.async(flags: .barrier) { [weak self] in
      guard let self else { return }
      for keyValue in keyValues {
        _info[keyValue.key] = keyValue.value
      }
    }
  }

  /// Sets a single key-value and immediately updates the Now Playing Info Center
  func set(keyValue: NowPlayingInfoKeyValue) {
    infoQueue.async(flags: .barrier) { [weak self] in
      guard let self else { return }
      _info[keyValue.key] = keyValue.value
      performUpdate()
    }
  }

  /// Explicitly updates the Now Playing Info Center with the current info.
  /// Thread-safe - can be called from any thread.
  /// Use after calling setWithoutUpdate() to commit batched changes.
  func update() {
    infoQueue.async(flags: .barrier) { [weak self] in
      guard let self else { return }
      performUpdate()
    }
  }

  /// Internal update method - assumes already inside barrier block.
  /// Use this from internal methods that are already synchronized to avoid nested dispatch.
  private func performUpdate() {
    let keys = _info.keys.sorted().joined(separator: ", ")
    let hasArtwork = _info[MPMediaItemPropertyArtwork] != nil
    logger.debug("performUpdate: setting nowPlayingInfo with \(_info.count) keys (hasArtwork=\(hasArtwork)): \(keys)")
    infoCenter.nowPlayingInfo = _info
  }

  /// Clears all Now Playing info
  func clear() {
    infoQueue.async(flags: .barrier) { [weak self] in
      guard let self else { return }
      _info = [:]
      infoCenter.nowPlayingInfo = nil
    }
  }
}
