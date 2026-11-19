import Foundation

enum BrowserError: LocalizedError {
  case contentNotFound(path: String)
  case invalidConfiguration(String)
  case callbackError(String)

  var errorDescription: String? {
    switch self {
    case .contentNotFound: "Content not found"
    case let .invalidConfiguration(message): "Invalid configuration: \(message)"
    case let .callbackError(message): message
    }
  }
}

extension BrowserError {
  /// The error for a request that no layer gives a base URL. A path no route matches has
  /// nowhere to be fetched from; a route that names a request is misconfigured.
  static func missingBaseUrl(path: String, routed: Bool) -> BrowserError {
    routed ? .invalidConfiguration("No URL configured for route") : .contentNotFound(path: path)
  }
}
