import Foundation
import Kingfisher

/// Why an artwork load failed: the HTTP status of a refused response, or the URLError code of a
/// failed request, when there is one. Its description never carries the URL.
struct ArtworkLoadFailure: Error, Equatable, CustomStringConvertible {
  let status: Int?
  let urlErrorCode: URLError.Code?
  /// The load was cancelled, not refused: nothing went wrong.
  let isCancellation: Bool
  private let detail: String

  init(status: Int? = nil, urlErrorCode: URLError.Code? = nil, isCancellation: Bool = false, detail: String = "") {
    self.status = status
    self.urlErrorCode = urlErrorCode
    self.isCancellation = isCancellation || urlErrorCode == .cancelled
    self.detail = detail
  }

  init(_ error: any Error) {
    switch error {
    case let KingfisherError.responseError(reason: .invalidHTTPStatusCode(response)):
      self.init(status: response.statusCode)
    case let KingfisherError.responseError(reason: .URLSessionError(underlying as URLError)):
      self.init(urlErrorCode: underlying.code)
    case let error as URLError:
      self.init(urlErrorCode: error.code)
    case is CancellationError:
      self.init(isCancellation: true)
    case let error as KingfisherError where error.isTaskCancelled:
      self.init(isCancellation: true, detail: "Kingfisher error \(error.errorCode)")
    case let error as KingfisherError:
      self.init(detail: "Kingfisher error \(error.errorCode)")
    default:
      self.init(detail: "\(type(of: error)) \((error as NSError).code)")
    }
  }

  /// The request could not reach the host (`URLError.isConnectivity`).
  var isConnectionFailure: Bool {
    urlErrorCode.map { URLError($0).isConnectivity } ?? false
  }

  /// True only for a failure a repeat may not meet: a connection failure, a server error, 408
  /// or 429.
  var isRetryable: Bool {
    if isConnectionFailure { return true }
    guard let status else { return false }
    return status >= 500 || status == 408 || status == 429
  }

  var description: String {
    let cause = if let status {
      "HTTP \(status)"
    } else if let urlErrorCode {
      "URLError \(urlErrorCode.rawValue)"
    } else {
      "failed"
    }
    return detail.isEmpty ? cause : "\(cause): \(detail)"
  }
}
