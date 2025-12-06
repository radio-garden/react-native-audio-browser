import AVFoundation
import Foundation

/**
 Observes player item notifications and invokes callbacks passed at initialization.
 */
class PlayerItemNotificationObserver {
  private let notificationCenter: NotificationCenter = .default

  private(set) weak var observingAVItem: AVPlayerItem?
  private(set) var isObserving: Bool = false

  private let onDidPlayToEndTime: () -> Void
  private let onFailedToPlayToEndTime: () -> Void

  init(
    onDidPlayToEndTime: @escaping () -> Void,
    onFailedToPlayToEndTime: @escaping () -> Void
  ) {
    self.onDidPlayToEndTime = onDidPlayToEndTime
    self.onFailedToPlayToEndTime = onFailedToPlayToEndTime
  }

  deinit {
    stopObservingCurrentItem()
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
      selector: #selector(avItemDidPlayToEndTime),
      name: NSNotification.Name.AVPlayerItemDidPlayToEndTime,
      object: avItem
    )
    notificationCenter.addObserver(
      self,
      selector: #selector(avItemFailedToPlayToEndTime),
      name: NSNotification.Name.AVPlayerItemFailedToPlayToEndTime,
      object: avItem
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
      object: observingAVItem
    )
    notificationCenter.removeObserver(
      self,
      name: NSNotification.Name.AVPlayerItemFailedToPlayToEndTime,
      object: observingAVItem
    )
    self.observingAVItem = nil
    isObserving = false
  }

  @objc private func avItemDidPlayToEndTime() {
    onDidPlayToEndTime()
  }

  @objc private func avItemFailedToPlayToEndTime() {
    onFailedToPlayToEndTime()
  }
}
