import AVFoundation
import Foundation

/**
 Observes player item notifications and invokes callbacks passed at initialization.
 */
@MainActor class PlayerItemNotificationObserver {
  private let notificationCenter: NotificationCenter = .default

  private(set) weak var observingAVItem: AVPlayerItem?
  private(set) var isObserving: Bool = false

  private let onDidPlayToEndTime: @MainActor () -> Void
  private let onFailedToPlayToEndTime: @MainActor (Error?) -> Void
  private let onPlaybackStalled: @MainActor () -> Void

  init(
    onDidPlayToEndTime: @escaping @MainActor () -> Void,
    onFailedToPlayToEndTime: @escaping @MainActor (Error?) -> Void,
    onPlaybackStalled: @escaping @MainActor () -> Void,
  ) {
    self.onDidPlayToEndTime = onDidPlayToEndTime
    self.onFailedToPlayToEndTime = onFailedToPlayToEndTime
    self.onPlaybackStalled = onPlaybackStalled
  }

  /**
   Will start observing notifications from an AVPlayerItem.

   - parameter avItem: The AVPlayerItem to observe.
   - important: Cannot observe more than one item at a time.
   */
  func startObserving(item avItem: AVPlayerItem) {
    stopObservingCurrentItem()
    observingAVItem = avItem
    isObserving = true
    notificationCenter.addObserver(
      self,
      selector: #selector(avItemDidPlayToEndTime(_:)),
      name: NSNotification.Name.AVPlayerItemDidPlayToEndTime,
      object: avItem,
    )
    notificationCenter.addObserver(
      self,
      selector: #selector(avItemFailedToPlayToEndTime(_:)),
      name: NSNotification.Name.AVPlayerItemFailedToPlayToEndTime,
      object: avItem,
    )
    notificationCenter.addObserver(
      self,
      selector: #selector(avItemPlaybackStalled(_:)),
      name: NSNotification.Name.AVPlayerItemPlaybackStalled,
      object: avItem,
    )
  }

  /**
   Stop receiving notifications for the current AVPlayerItem.
   */
  func stopObservingCurrentItem() {
    guard let observingAVItem, isObserving else {
      return
    }
    notificationCenter.removeObserver(
      self,
      name: NSNotification.Name.AVPlayerItemDidPlayToEndTime,
      object: observingAVItem,
    )
    notificationCenter.removeObserver(
      self,
      name: NSNotification.Name.AVPlayerItemFailedToPlayToEndTime,
      object: observingAVItem,
    )
    notificationCenter.removeObserver(
      self,
      name: NSNotification.Name.AVPlayerItemPlaybackStalled,
      object: observingAVItem,
    )
    self.observingAVItem = nil
    isObserving = false
  }

  @objc private nonisolated func avItemDidPlayToEndTime(_ notification: Notification) {
    let identity = (notification.object as? AVPlayerItem).map(ObjectIdentifier.init)
    Task { @MainActor in
      guard self.isObserving(identity) else { return }
      self.onDidPlayToEndTime()
    }
  }

  @objc private nonisolated func avItemFailedToPlayToEndTime(_ notification: Notification) {
    // Extract the error from the notification's userInfo
    // AVPlayerItemFailedToPlayToEndTimeErrorKey contains the actual error
    let error = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
    let identity = (notification.object as? AVPlayerItem).map(ObjectIdentifier.init)
    Task { @MainActor in
      guard self.isObserving(identity) else { return }
      self.onFailedToPlayToEndTime(error)
    }
  }

  // Fired when the item's buffer empties mid-playback. iOS exposes no
  // rebuffer-vs-initial signal otherwise, and AVPlayer can stay parked in
  // `.waitingToPlayAtSpecifiedRate` after data returns — the listener nudges it.
  @objc private nonisolated func avItemPlaybackStalled(_ notification: Notification) {
    let identity = (notification.object as? AVPlayerItem).map(ObjectIdentifier.init)
    Task { @MainActor in
      guard self.isObserving(identity) else { return }
      self.onPlaybackStalled()
    }
  }

  /// True when `identity` still names the observed item. Notifications are posted off the main
  /// thread, so the main-actor hop can land after the observer switched items — without this
  /// check the old item's late delivery (e.g. a stale failure) reaches the new item's handlers.
  private func isObserving(_ identity: ObjectIdentifier?) -> Bool {
    identity != nil && observingAVItem.map(ObjectIdentifier.init) == identity
  }
}
