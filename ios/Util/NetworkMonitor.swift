import Foundation
import Network
import os.log

/// The device's connectivity from NWPathMonitor; `changed` emits each change on the main thread.
final class NetworkMonitor: @unchecked Sendable {
  // MARK: - Properties

  private let logger = Logger(subsystem: "com.audiobrowser", category: "NetworkMonitor")
  private let monitor: NWPathMonitor
  private let queue = DispatchQueue(label: "com.audiobrowser.networkmonitor")

  // Guards `_isOnline` only; never held across an emit.
  private let lock = NSLock()
  private var _isOnline = false

  /// Readable from any thread, so the JS thread's `getOnline` needs no main-queue hop.
  var isOnline: Bool {
    lock.lock()
    defer { lock.unlock() }
    return _isOnline
  }

  private func apply(_ newStatus: Bool) {
    lock.lock()
    let changed = _isOnline != newStatus
    let oldValue = _isOnline
    _isOnline = newStatus
    lock.unlock()

    guard changed else { return }
    logger.notice("Network status changed: \(oldValue) -> \(newStatus)")
    DispatchQueue.main.async { [weak self] in
      self?.changed.emit(newStatus)
    }
  }

  let changed = Emitter<Bool>()

  // MARK: - Initialization

  init() {
    monitor = NWPathMonitor()

    // Seeded before `start`: written after it, a `currentPath` the monitor hasn't finished
    // evaluating could clobber the handler's real update. The handler stays the last writer.
    let initialStatus = monitor.currentPath.status.isOnline
    logger.notice("NetworkMonitor initialized, initial isOnline=\(initialStatus)")
    _isOnline = initialStatus

    monitor.pathUpdateHandler = { [weak self] path in
      self?.apply(path.status.isOnline)
    }

    monitor.start(queue: queue)
  }

  func destroy() {
    monitor.cancel()
  }

  deinit {
    destroy()
  }
}

extension NetworkMonitor: NetworkStatusProviding {}
