import Foundation
import Network
import os.log

/// Monitors network connectivity state using NWPathMonitor.
/// Notifies listeners when the connection state changes.
final class NetworkMonitor: @unchecked Sendable {
  // MARK: - Properties

  private let logger = Logger(subsystem: "com.audiobrowser", category: "NetworkMonitor")
  private let monitor: NWPathMonitor
  private let queue = DispatchQueue(label: "com.audiobrowser.networkmonitor")

  /// Guards `_isOnline` only. Never held across `onChanged` — see `apply`.
  private let lock = NSLock()
  private var _isOnline = false

  /// Current network connectivity state. Readable from any thread: the value is
  /// lock-protected rather than confined, so callers on the JS thread (Nitro's
  /// `getOnline`) don't have to block on a main-queue hop to read a Bool.
  ///
  /// The broadcast is main-confined even though the value isn't — `onChanged`
  /// fans out to the JS bridge, a now-playing re-render and a player reload, so
  /// it must not run under the lock (reentrancy) or off the main actor.
  var isOnline: Bool {
    lock.lock()
    defer { lock.unlock() }
    return _isOnline
  }

  /// Stores a new status and, if it actually changed, broadcasts on main —
  /// outside the lock.
  private func apply(_ newStatus: Bool) {
    lock.lock()
    let changed = _isOnline != newStatus
    let oldValue = _isOnline
    _isOnline = newStatus
    lock.unlock()

    guard changed else { return }
    logger.notice("Network status changed: \(oldValue) -> \(newStatus)")
    DispatchQueue.main.async { [weak self] in
      self?.onChanged?(newStatus)
    }
  }

  /// Invoked on every connectivity change. Wired centrally in `HybridAudioBrowser.setupPlayer` and
  /// fans out to the JS event bridge, the now-playing re-render (so the offline/online label tracks
  /// connectivity), and the player's reconnect-reload.
  var onChanged: ((Bool) -> Void)?

  // MARK: - Initialization

  init() {
    monitor = NWPathMonitor()

    // Seed BEFORE `start`. `start` publishes `self` to the monitor queue, so any
    // write after it races the handler's locked write — and would clobber a real
    // update with a `currentPath` the monitor hasn't finished evaluating. Nothing
    // recovers from that: the handler has already fired, `onChanged` isn't wired
    // until setupPlayer, and the stale value then drives `getOnline()`, the JS
    // event, and RetryManager's offline parking until the next genuine path
    // change. Seeding first is a best-effort guess; the handler fires shortly
    // after `start` and is the authoritative last writer.
    let initialStatus = monitor.currentPath.status == .satisfied
    logger.notice("NetworkMonitor initialized, initial isOnline=\(initialStatus)")
    _isOnline = initialStatus

    monitor.pathUpdateHandler = { [weak self] path in
      self?.apply(path.status == .satisfied)
    }

    monitor.start(queue: queue)
  }

  /// Stops monitoring and cleans up resources.
  func destroy() {
    monitor.cancel()
  }

  deinit {
    destroy()
  }
}

extension NetworkMonitor: NetworkStatusProviding {}
