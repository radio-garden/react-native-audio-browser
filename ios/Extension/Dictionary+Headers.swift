import Foundation

extension [String: String] {
  /// Whether these headers set `name`, in any case: HTTP header names are case-insensitive.
  func hasHeader(_ name: String) -> Bool {
    keys.contains { $0.caseInsensitiveCompare(name) == .orderedSame }
  }
}
