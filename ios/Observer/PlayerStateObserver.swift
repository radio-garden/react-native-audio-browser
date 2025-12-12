import AVFoundation
import Foundation

/**
 Observes player state changes and invokes callbacks passed at initialization.
 */
final class PlayerStateObserver: NSObject {
  nonisolated(unsafe) private static var context = 0
  private let main: DispatchQueue = .main

  private enum AVPlayerKeyPath {
    static let status = #keyPath(AVPlayer.status)
    static let timeControlStatus = #keyPath(AVPlayer.timeControlStatus)
  }

  private let statusChangeOptions: NSKeyValueObservingOptions = [.new, .initial]
  private let timeControlStatusChangeOptions: NSKeyValueObservingOptions = [.new]
  private(set) var isObserving: Bool = false

  weak var avPlayer: AVPlayer? {
    willSet {
      stopObserving()
    }
  }

  private let onStatusChange: (AVPlayer.Status) -> Void
  private let onTimeControlStatusChange: (AVPlayer.TimeControlStatus) -> Void

  init(
    onStatusChange: @escaping (AVPlayer.Status) -> Void,
    onTimeControlStatusChange: @escaping (AVPlayer.TimeControlStatus) -> Void,
  ) {
    self.onStatusChange = onStatusChange
    self.onTimeControlStatusChange = onTimeControlStatusChange
  }

  deinit {
    stopObserving()
  }

  /**
   Start receiving events from this observer.
   */
  func startObserving() {
    if isObserving { return }
    guard let avPlayer else {
      return
    }
    isObserving = true
    avPlayer.addObserver(
      self,
      forKeyPath: AVPlayerKeyPath.status,
      options: statusChangeOptions,
      context: &PlayerStateObserver.context,
    )
    avPlayer.addObserver(
      self,
      forKeyPath: AVPlayerKeyPath.timeControlStatus,
      options: timeControlStatusChangeOptions,
      context: &PlayerStateObserver.context,
    )
  }

  func stopObserving() {
    guard let avPlayer, isObserving else {
      return
    }
    avPlayer.removeObserver(
      self,
      forKeyPath: AVPlayerKeyPath.status,
      context: &PlayerStateObserver.context,
    )
    avPlayer.removeObserver(
      self,
      forKeyPath: AVPlayerKeyPath.timeControlStatus,
      context: &PlayerStateObserver.context,
    )
    isObserving = false
  }

  override func observeValue(
    forKeyPath keyPath: String?,
    of object: Any?,
    change: [NSKeyValueChangeKey: Any]?,
    context: UnsafeMutableRawPointer?,
  ) {
    guard context == &PlayerStateObserver.context, let observedKeyPath = keyPath else {
      super.observeValue(forKeyPath: keyPath, of: object, change: change, context: context)
      return
    }

    switch observedKeyPath {
    case AVPlayerKeyPath.status:
      handleStatusChange(change)

    case AVPlayerKeyPath.timeControlStatus:
      handleTimeControlStatusChange(change)

    default:
      break
    }
  }

  private func handleStatusChange(_ change: [NSKeyValueChangeKey: Any]?) {
    let status: AVPlayer.Status = if let statusNumber = change?[.newKey] as? NSNumber {
      AVPlayer.Status(rawValue: statusNumber.intValue)!
    } else {
      .unknown
    }
    onStatusChange(status)
  }

  private func handleTimeControlStatusChange(_ change: [NSKeyValueChangeKey: Any]?) {
    let status: AVPlayer.TimeControlStatus
    if let statusNumber = change?[.newKey] as? NSNumber {
      status = AVPlayer.TimeControlStatus(rawValue: statusNumber.intValue)!
      onTimeControlStatusChange(status)
    }
  }
}
