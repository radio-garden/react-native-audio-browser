import Network

/// Connectivity; a fake stands in for `NetworkMonitor` in tests.
protocol NetworkStatusProviding: AnyObject, Sendable {
  var isOnline: Bool { get }
}

extension NWPath.Status {
  /// Offline only when no route exists: a path that requires a connection (a VPN on demand, some
  /// cellular paths) comes up when one is made, so the retry loop must not wait for it.
  var isOnline: Bool { self != .unsatisfied }
}
