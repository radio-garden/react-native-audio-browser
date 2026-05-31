import AVFoundation
import Foundation
@preconcurrency import MediaPlayer
import os.log

// MPRemoteCommandCenter is not Sendable, but we only use it on the main thread
extension MPRemoteCommandCenter: @retroactive @unchecked Sendable {}

/// Controller for managing Now Playing info.
///
/// A `MPNowPlayingSession` bound to the linked AVPlayer publishes now-playing info
/// automatically: the system derives elapsed time, playback rate, and duration from
/// the player, so the per-second clock stays out of our code (it extrapolates the
/// scrubber). We supply the two things automatic publishing does NOT provide:
/// metadata (title/artist/artwork), attached to `AVPlayerItem.nowPlayingInfo` (the
/// supported channel under automatic publishing); and the explicit `playbackState`
/// that CarPlay / Control Center read for their play/pause button (see `setPlaybackState`).
@MainActor
class NowPlayingInfoController {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "NowPlayingInfoController")

  private var _info: [String: Any] = [:]

  /// Last play/pause state pushed to the session's center, to skip redundant writes.
  private var _playbackState: MPNowPlayingPlaybackState?

  /// The session bound to the linked AVPlayer; held so it stays alive while publishing.
  private var nowPlayingSession: MPNowPlayingSession?

  /// The linked AVPlayer — metadata is attached to its current item.
  private weak var linkedPlayer: AVPlayer?

  /// The current remote command center - the session's while linked, else the shared one.
  private(set) var remoteCommandCenter: MPRemoteCommandCenter = .shared()

  required init() {}

  /// Callback invoked when the remote command center changes (session created/destroyed).
  var onRemoteCommandCenterChanged: ((MPRemoteCommandCenter) -> Void)?

  /// Binds an AVPlayer to a new auto-publishing `MPNowPlayingSession`.
  ///
  /// - Important: This creates an `MPNowPlayingSession` with its own `remoteCommandCenter`;
  ///   the `onRemoteCommandCenterChanged` callback is invoked with the new command center.
  func linkPlayer(_ player: AVPlayer) {
    logger.info("Linking AVPlayer to MPNowPlayingSession for automatic publishing")

    linkedPlayer = player

    let session = MPNowPlayingSession(players: [player])
    nowPlayingSession = session
    _playbackState = nil // new center starts at .unknown; force the next write
    remoteCommandCenter = session.remoteCommandCenter

    session.automaticallyPublishesNowPlayingInfo = true

    session.becomeActiveIfPossible { success in
      self.logger.info("MPNowPlayingSession becomeActiveIfPossible: \(success)")
    }

    onRemoteCommandCenterChanged?(remoteCommandCenter)
  }

  /// Unlinks the AVPlayer, tearing down the session and restoring the shared command center.
  func unlinkPlayer() {
    logger.info("Unlinking AVPlayer from MPNowPlayingSession")

    linkedPlayer = nil
    nowPlayingSession?.automaticallyPublishesNowPlayingInfo = false
    nowPlayingSession = nil
    _playbackState = nil

    remoteCommandCenter = MPRemoteCommandCenter.shared()
    onRemoteCommandCenterChanged?(remoteCommandCenter)
  }

  /// Keys the session derives from the player itself — we never set these manually,
  /// the system owns elapsed/duration/rate.
  private static let autoPublishedKeys: Set<String> = [
    MPNowPlayingInfoPropertyElapsedPlaybackTime,
    MPMediaItemPropertyPlaybackDuration,
    MPNowPlayingInfoPropertyPlaybackRate,
  ]

  /// Whether a new value matches what's already in `_info` — used to avoid
  /// re-publishing unchanged metadata. Compares the comparable scalar types we
  /// store (title/artist/album strings, isLiveStream); non-comparable values
  /// (e.g. artwork) are treated as changed.
  private func valueUnchanged(_ key: String, _ newValue: Any?) -> Bool {
    switch (_info[key], newValue) {
    case (nil, nil): return true
    case let (a as String, b as String): return a == b
    case let (a as Bool, b as Bool): return a == b
    case let (a as NSNumber, b as NSNumber): return a == b
    default: return false
    }
  }

  /// Sets key-values and immediately updates Now Playing.
  /// Playback-dynamics keys (elapsed/duration/rate) are owned by the session and skipped.
  func set(keyValues: [NowPlayingInfoKeyValue]) {
    var changed = false
    for kv in keyValues where !Self.autoPublishedKeys.contains(kv.key) && !valueUnchanged(kv.key, kv.value) {
      _info[kv.key] = kv.value
      changed = true
    }
    if changed { performUpdate() }
  }

  /// Sets a single key-value and immediately updates Now Playing.
  func set(keyValue: NowPlayingInfoKeyValue) {
    guard !Self.autoPublishedKeys.contains(keyValue.key), !valueUnchanged(keyValue.key, keyValue.value) else { return }
    _info[keyValue.key] = keyValue.value
    performUpdate()
  }

  /// Attaches metadata to the current item — the supported channel under automatic publishing.
  private func performUpdate() {
    linkedPlayer?.currentItem?.nowPlayingInfo = _info
  }

  /// Prepares an AVPlayerItem with stored metadata before it becomes current, so the
  /// session has metadata from the moment the item starts playing.
  /// Call this before `replaceCurrentItem(with:)`.
  func prepareItem(_ item: AVPlayerItem) {
    item.nowPlayingInfo = _info
  }

  /// Clears all Now Playing info.
  func clear() {
    _info = [:]
    linkedPlayer?.currentItem?.nowPlayingInfo = nil
  }

  /// Sets the play/pause state CarPlay / Control Center show for their transport
  /// button. Automatic publishing fills the info dict (metadata/elapsed/rate) but
  /// not the explicit `playbackState`, so the coordinator pushes the user's
  /// play/pause intent here on intent/state changes.
  func setPlaybackState(playing: Bool) {
    let state: MPNowPlayingPlaybackState = playing ? .playing : .paused
    guard state != _playbackState else { return }
    _playbackState = state
    nowPlayingSession?.nowPlayingInfoCenter.playbackState = state
  }
}
