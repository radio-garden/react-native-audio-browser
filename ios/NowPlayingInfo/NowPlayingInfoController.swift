import AVFoundation
import Foundation
import MediaPlayer
import os.log

/// Thread-safe controller for managing Now Playing info.
///
/// On iOS 16+, uses `MPNowPlayingSession` with `automaticallyPublishNowPlayingInfo` to let the system
/// automatically update elapsed time, playback rate, and duration. We still manually set metadata
/// (title, artist, artwork) which the automatic publishing doesn't handle.
///
/// On iOS 15.x, falls back to manual `MPNowPlayingInfoCenter` updates for all properties.
class NowPlayingInfoController {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "NowPlayingInfoController")
  private var infoQueue = DispatchQueue(
    label: "NowPlayingInfoController.infoQueue",
    attributes: .concurrent,
  )

  private(set) var infoCenter: NowPlayingInfoCenter
  private var _info: [String: Any] = [:]

  /// Wrapper for MPNowPlayingSession (iOS 16+) to avoid @available on stored property
  private var _nowPlayingSession: Any?

  /// The linked AVPlayer (iOS 16+)
  private weak var _linkedPlayer: AVPlayer?

  /// Whether automatic publishing is currently active (iOS 16+ with linked player)
  private var isAutomaticPublishingEnabled: Bool = false

  /// The current remote command center - either the session's (iOS 16+) or shared
  private var _remoteCommandCenter: MPRemoteCommandCenter = MPRemoteCommandCenter.shared()

  /// Returns the appropriate remote command center.
  /// On iOS 16+ with an active session, returns the session's command center.
  /// Otherwise returns the shared command center.
  var remoteCommandCenter: MPRemoteCommandCenter {
    infoQueue.sync { _remoteCommandCenter }
  }

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

  /// Callback invoked when the remote command center changes (iOS 16+ session created/destroyed)
  /// Called on the main thread.
  var onRemoteCommandCenterChanged: (@Sendable (MPRemoteCommandCenter) -> Void)?

  /// Links an AVPlayer to enable automatic Now Playing publishing on iOS 16+.
  /// On older iOS versions, this is a no-op.
  ///
  /// When linked:
  /// - System automatically updates elapsed time, playback rate, and duration
  /// - Metadata (title, artist, artwork) is set via `AVPlayerItem.nowPlayingInfo`
  /// - The session's `remoteCommandCenter` must be used for remote commands
  ///
  /// - Important: On iOS 16+, this creates an `MPNowPlayingSession` with its own `remoteCommandCenter`.
  ///   The `onRemoteCommandCenterChanged` callback will be invoked with the new command center.
  func linkPlayer(_ player: AVPlayer) {
    if #available(iOS 16.0, *) {
      infoQueue.async(flags: .barrier) { [weak self] in
        guard let self else { return }
        logger.info("Linking AVPlayer to MPNowPlayingSession for automatic publishing")

        // Store player reference
        _linkedPlayer = player

        // Create a new session with the player (players is read-only after init)
        let session = MPNowPlayingSession(players: [player])
        _nowPlayingSession = session

        // Use the session's remote command center instead of the shared one
        _remoteCommandCenter = session.remoteCommandCenter

        // Enable automatic publishing - system tracks elapsed time and duration from AVPlayer
        session.automaticallyPublishesNowPlayingInfo = true

        // Become the active session
        session.becomeActiveIfPossible { [weak self] success in
          self?.logger.info("MPNowPlayingSession becomeActiveIfPossible: \(success)")
        }

        isAutomaticPublishingEnabled = true

        // Notify about the command center change on main thread
        let newCommandCenter = _remoteCommandCenter
        let callback = onRemoteCommandCenterChanged
        DispatchQueue.main.async {
          callback?(newCommandCenter)
        }
      }
    } else {
      logger.debug("linkPlayer: iOS 16+ required for automatic publishing, using manual updates")
    }
  }

  /// Unlinks the AVPlayer, disabling automatic publishing.
  func unlinkPlayer() {
    if #available(iOS 16.0, *) {
      infoQueue.async(flags: .barrier) { [weak self] in
        guard let self else { return }
        logger.info("Unlinking AVPlayer from MPNowPlayingSession")

        // Clear player reference
        _linkedPlayer = nil

        // Disable automatic publishing and release the session
        if let session = _nowPlayingSession as? MPNowPlayingSession {
          session.automaticallyPublishesNowPlayingInfo = false
        }
        _nowPlayingSession = nil
        isAutomaticPublishingEnabled = false

        // Revert to shared command center
        _remoteCommandCenter = MPRemoteCommandCenter.shared()

        // Notify about the command center change on main thread
        let newCommandCenter = _remoteCommandCenter
        let callback = onRemoteCommandCenterChanged
        DispatchQueue.main.async {
          callback?(newCommandCenter)
        }
      }
    }
  }

  /// Keys that are automatically published by MPNowPlayingSession on iOS 16+.
  /// When automatic publishing is enabled, we skip setting these manually.
  private static let autoPublishedKeys: Set<String> = [
    MPNowPlayingInfoPropertyElapsedPlaybackTime,
    MPMediaItemPropertyPlaybackDuration,
    MPNowPlayingInfoPropertyPlaybackRate,
  ]

  /// Returns true if the key is auto-published and automatic publishing is enabled
  private func shouldSkipKey(_ key: String) -> Bool {
    isAutomaticPublishingEnabled && Self.autoPublishedKeys.contains(key)
  }

  /// Sets key-values and immediately updates the Now Playing Info Center.
  /// When automatic publishing is enabled (iOS 16+), playback-related keys are skipped.
  func set(keyValues: [NowPlayingInfoKeyValue]) {
    infoQueue.async(flags: .barrier) { [weak self] in
      guard let self else { return }
      for keyValue in keyValues {
        if !shouldSkipKey(keyValue.key) {
          _info[keyValue.key] = keyValue.value
        }
      }
      performUpdate()
    }
  }

  /// Sets key-values without updating the Now Playing Info Center.
  /// Useful for batching multiple updates - call update() when ready to commit.
  /// When automatic publishing is enabled (iOS 16+), playback-related keys are skipped.
  func setWithoutUpdate(keyValues: [NowPlayingInfoKeyValue]) {
    infoQueue.async(flags: .barrier) { [weak self] in
      guard let self else { return }
      for keyValue in keyValues {
        if !shouldSkipKey(keyValue.key) {
          _info[keyValue.key] = keyValue.value
        }
      }
    }
  }

  /// Sets a single key-value and immediately updates the Now Playing Info Center.
  /// When automatic publishing is enabled (iOS 16+), playback-related keys are skipped.
  func set(keyValue: NowPlayingInfoKeyValue) {
    infoQueue.async(flags: .barrier) { [weak self] in
      guard let self else { return }
      if !shouldSkipKey(keyValue.key) {
        _info[keyValue.key] = keyValue.value
        performUpdate()
      }
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
  ///
  /// On iOS 16+ with automatic publishing:
  /// - Sets metadata on `AVPlayerItem.nowPlayingInfo`
  /// - Skips elapsed time and duration (system handles these)
  ///
  /// On iOS 15.x or without automatic publishing:
  /// - Sets all info on `MPNowPlayingInfoCenter.default().nowPlayingInfo`
  private func performUpdate() {
    let keys = _info.keys.sorted().joined(separator: ", ")
    let hasArtwork = _info[MPMediaItemPropertyArtwork] != nil
    let playbackRate = _info[MPNowPlayingInfoPropertyPlaybackRate] as? NSNumber
    logger.debug("performUpdate: setting nowPlayingInfo with \(self._info.count) keys (hasArtwork=\(hasArtwork), playbackRate=\(playbackRate?.doubleValue ?? -1), autoPublishing=\(self.isAutomaticPublishingEnabled)): \(keys)")

    if #available(iOS 16.0, *), isAutomaticPublishingEnabled {
      // iOS 16+ with automatic publishing: set metadata on player item
      // If no current item yet, info is stored in _info and will be applied when reapplyToCurrentItem() is called
      _linkedPlayer?.currentItem?.nowPlayingInfo = _info
    } else {
      // iOS 15.x or no automatic publishing: use MPNowPlayingInfoCenter
      infoCenter.nowPlayingInfo = _info
    }
  }

  /// Prepares an AVPlayerItem with stored metadata before it becomes current.
  /// Call this before `replaceCurrentItem(with:)` so the item has metadata from the start.
  func prepareItem(_ item: AVPlayerItem) {
    infoQueue.sync {
      if #available(iOS 16.0, *), isAutomaticPublishingEnabled {
        item.nowPlayingInfo = _info
      }
    }
  }

  /// Clears all Now Playing info
  func clear() {
    infoQueue.async(flags: .barrier) { [weak self] in
      guard let self else { return }
      _info = [:]

      // With automatic publishing, the session handles clearing when the player stops
      // Without automatic publishing, we need to clear MPNowPlayingInfoCenter manually
      if !isAutomaticPublishingEnabled {
        infoCenter.nowPlayingInfo = nil
      }
    }
  }

  /// Sets the playback state (required for CarPlay Now Playing to show correct play/pause state)
  func setPlaybackState(_ state: MPNowPlayingPlaybackState) {
    infoQueue.async(flags: .barrier) { [weak self] in
      guard let self else { return }
      logger.debug("setPlaybackState: \(state.rawValue)")
      infoCenter.playbackState = state
    }
  }
}
