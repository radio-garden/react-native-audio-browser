import Foundation

extension NavigationError {
  /// Converts a generic Error to a NavigationError.
  /// Handles BrowserError, HttpClient.HttpException, URLError, and fallback.
  static func from(_ error: Error) -> NavigationError {
    if let browserError = error as? BrowserError {
      switch browserError {
      case .contentNotFound:
        NavigationError(code: .contentNotFound, message: browserError.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
      case let .httpError(code, _):
        NavigationError(code: .httpError, message: browserError.localizedDescription, statusCode: Double(code), statusCodeSuccess: (200 ... 299).contains(code))
      case .networkError:
        NavigationError(code: .networkError, message: browserError.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
      case .invalidConfiguration:
        NavigationError(code: .unknownError, message: browserError.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
      case .callbackError:
        NavigationError(code: .callbackError, message: browserError.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
      }
    } else if let httpError = error as? HttpClient.HttpException {
      NavigationError(code: .httpError, message: httpError.localizedDescription, statusCode: Double(httpError.code), statusCodeSuccess: (200 ... 299).contains(httpError.code))
    } else if error is URLError {
      NavigationError(code: .networkError, message: error.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
    } else {
      NavigationError(code: .unknownError, message: error.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
    }
  }

  /// Returns the default user-facing formatted error.
  func defaultFormatted() -> FormattedNavigationError {
    let title: String = switch code {
    case .contentNotFound:
      "Content Not Found"
    case .networkError:
      "Network Error"
    case .httpError:
      if let statusCode {
        HTTPURLResponse.localizedString(forStatusCode: Int(statusCode)).capitalized
      } else {
        "Server Error"
      }
    case .callbackError:
      "Error"
    case .unknownError:
      "Error"
    }
    return FormattedNavigationError(title: title, message: message)
  }
}
