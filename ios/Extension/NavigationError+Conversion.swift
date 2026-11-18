import Foundation

extension NavigationError {
  /// Maps any thrown error to a NavigationError. `BrowserError.invalidConfiguration`,
  /// and any error that is not a BrowserError, HttpException or URLError, becomes `.unknownError`.
  static func from(_ error: Error) -> NavigationError {
    if let browserError = error as? BrowserError {
      switch browserError {
      case .contentNotFound:
        NavigationError(code: .contentNotFound, message: browserError.localizedDescription, statusCode: nil, statusCodeSuccess: nil)
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

  /// The English copy passed to the app's `formatNavigationError`, and used when it has none.
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
    case .emptyContent:
      // Not a failure: a container with no children (ADR 0001).
      "Nothing here"
    case .timeout:
      "Couldn't load"
    }
    return FormattedNavigationError(title: title, message: message.isEmpty ? nil : message)
  }
}

extension NavigationError {
  /// A code-only error: the copy comes from the app's `formatNavigationError` (ADR 0001).
  static func code(_ code: NavigationErrorType) -> NavigationError {
    NavigationError(code: code, message: "", statusCode: nil, statusCodeSuccess: nil)
  }
}

extension NavigationErrorKind {
  init(path: String) {
    self = BrowserPathHelper.isSearchPath(path) ? .search : .browse
  }
}
