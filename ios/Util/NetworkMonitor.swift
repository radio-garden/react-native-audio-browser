import Foundation
import Network

/// Monitors network connectivity state using NWPathMonitor.
/// Notifies listeners when the connection state changes.
class NetworkMonitor {
  // MARK: - Properties

  private let monitor: NWPathMonitor
  private let queue = DispatchQueue(label: "com.audiobrowser.networkmonitor")

  /// Current network connectivity state
  private(set) var isOnline: Bool = false {
    didSet {
      if oldValue != isOnline {
        DispatchQueue.main.async { [weak self] in
          guard let self else { return }
          onChanged?(isOnline)
        }
      }
    }
  }

  /// Callback invoked when network state changes
  var onChanged: ((Bool) -> Void)?

  // MARK: - Initialization

  init() {
    monitor = NWPathMonitor()

    // Set up handler before starting
    monitor.pathUpdateHandler = { [weak self] path in
      let newStatus = path.status == .satisfied
      DispatchQueue.main.async {
        self?.isOnline = newStatus
      }
    }

    // Start monitoring
    monitor.start(queue: queue)

    // Read initial state after starting (currentPath is now valid)
    isOnline = monitor.currentPath.status == .satisfied
  }

  // MARK: - Public Methods

  /// Gets the current network connectivity state.
  /// - Returns: true if device is online, false otherwise
  func getOnline() -> Bool {
    isOnline
  }

  /// Stops monitoring and cleans up resources.
  func destroy() {
    monitor.cancel()
  }

  deinit {
    destroy()
  }
}
