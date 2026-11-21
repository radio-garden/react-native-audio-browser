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

extension BrowserError {
  /// Runs `body`, a call into a JS callback: what it throws becomes `.callbackError` with its
  /// message, except a cancellation and a `BrowserError`, which pass through.
  nonisolated(nonsending) static func fromCallback<T>(
    _ body: nonisolated(nonsending)() async throws -> T,
  ) async throws -> T {
    do {
      return try await body()
    } catch let error as CancellationError {
      throw error
    } catch let error as BrowserError {
      throw error
    } catch {
      throw BrowserError.callbackError(error.localizedDescription)
    }
  }
}
