import AVFoundation
import Foundation
@preconcurrency import MediaPlayer
import os.log

/// Publishes now-playing metadata through an auto-publishing `MPNowPlayingSession` bound to the
/// linked AVPlayer. The session derives elapsed time, rate and duration from the player; this
/// supplies the rest (title, artist, artwork) on `AVPlayerItem.nowPlayingInfo`. There is no
/// `playbackState`: iOS drops it without a private entitlement (docs/adr/0005).
@MainActor
class NowPlayingInfoController {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "NowPlayingInfoController")

  private var _info: [String: Any] = [:]

  private var nowPlayingSession: MPNowPlayingSession?

  private weak var linkedPlayer: AVPlayer?

  required init() {}

  /// Binds the player to a new auto-publishing session and returns that session's own remote
  /// command center, which remote commands must now be enabled on.
  func linkPlayer(_ player: AVPlayer) -> MPRemoteCommandCenter {
    logger.notice("Linking AVPlayer to MPNowPlayingSession for automatic publishing")

    linkedPlayer = player

    let session = MPNowPlayingSession(players: [player])
    nowPlayingSession = session

    session.automaticallyPublishesNowPlayingInfo = true

    session.becomeActiveIfPossible { success in
      self.logger.notice("MPNowPlayingSession becomeActiveIfPossible: \(success)")
    }
    return session.remoteCommandCenter
  }

  /// Re-requests activation of the linked session; no-op while it is active. Call whenever the
  /// audio session (re)activates: `linkPlayer` runs before it is active, the system can decline
  /// then, and if the first load fails no audio output ever elects the app instead.
  func reactivateSessionIfNeeded() {
    guard let session = nowPlayingSession, !session.isActive else { return }
    logger.notice("MPNowPlayingSession inactive (canBecomeActive: \(session.canBecomeActive)) — re-requesting activation")
    session.becomeActiveIfPossible { success in
      self.logger.notice("MPNowPlayingSession re-activation: \(success)")
    }
  }

  /// Drops the session along with its command center. Remote commands never fall back to the
  /// shared center, whose targets belong to the host app.
  func unlinkPlayer() {
    logger.info("Unlinking AVPlayer from MPNowPlayingSession")

    linkedPlayer = nil
    nowPlayingSession?.automaticallyPublishesNowPlayingInfo = false
    nowPlayingSession = nil
  }

  // The session derives these from the player; setting them too would fight it.
  private static let autoPublishedKeys: Set<String> = [
    MPNowPlayingInfoPropertyElapsedPlaybackTime,
    MPMediaItemPropertyPlaybackDuration,
    MPNowPlayingInfoPropertyPlaybackRate,
  ]

  // Values of any other type (artwork) always count as changed.
  private func valueUnchanged(_ key: String, _ newValue: Any?) -> Bool {
    switch (_info[key], newValue) {
    case (nil, nil): true
    case let (a as String, b as String): a == b
    case let (a as Bool, b as Bool): a == b
    case let (a as NSNumber, b as NSNumber): a == b
    default: false
    }
  }

  /// Sets now-playing fields by their `MPMediaItemProperty*` / `MPNowPlayingInfoProperty*` keys
  /// (nil removes one) and publishes if any changed. Elapsed time, duration and rate are ignored:
  /// the session publishes those. Returns whether anything changed.
  @discardableResult
  func set(_ fields: [String: Any?]) -> Bool {
    var changed = false
    for (key, value) in fields where !Self.autoPublishedKeys.contains(key) && !valueUnchanged(key, value) {
      _info[key] = value
      changed = true
    }
    if changed { performUpdate() }
    return changed
  }

  // The item is the supported channel under automatic publishing. Before one exists (its URL
  // still resolving) the info goes to the session's center, so the surface isn't blank
  // meanwhile; `prepareItem` hands it to the item channel.
  private func performUpdate() {
    if let item = linkedPlayer?.currentItem {
      item.nowPlayingInfo = _info
    } else {
      nowPlayingSession?.nowPlayingInfoCenter.nowPlayingInfo = _info
    }
  }

  /// Copies the current info onto an item. Call before `replaceCurrentItem(with:)`, so the
  /// session has it from the item's first moment.
  func prepareItem(_ item: AVPlayerItem) {
    item.nowPlayingInfo = _info
  }

  func clear() {
    _info = [:]
    linkedPlayer?.currentItem?.nowPlayingInfo = nil
    nowPlayingSession?.nowPlayingInfoCenter.nowPlayingInfo = nil
  }
}
