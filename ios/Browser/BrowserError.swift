import Foundation

/// Custom browser errors.
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
