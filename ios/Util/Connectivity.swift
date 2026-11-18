import Network

protocol NetworkStatusProviding: AnyObject, Sendable {
  var isOnline: Bool { get }
}

extension NWPath.Status {
  /// Offline only when no route exists: a path that requires a connection (VPN on demand, some
  /// cellular paths) comes up when one is made, so a retry must not wait for it.
  var isOnline: Bool { self != .unsatisfied }
}
