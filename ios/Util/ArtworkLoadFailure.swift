import Foundation
import Kingfisher

/// Why an artwork load failed: the HTTP status of a refused response, or the URLError code of a
/// failed request, when there is one.
struct ArtworkLoadFailure: Error, Equatable, CustomStringConvertible {
  let status: Int?
  let urlErrorCode: URLError.Code?
  /// The request never got an answer it could use: a URLError or a refused response.
  private let isNetwork: Bool
  private let detail: String

  init(status: Int? = nil, urlErrorCode: URLError.Code? = nil, detail: String = "") {
    self.init(status: status, urlErrorCode: urlErrorCode, isNetwork: status != nil || urlErrorCode != nil, detail: detail)
  }

  private init(status: Int?, urlErrorCode: URLError.Code?, isNetwork: Bool, detail: String) {
    self.status = status
    self.urlErrorCode = urlErrorCode
    self.isNetwork = isNetwork
    self.detail = detail
  }

  init(_ error: any Error) {
    switch error {
    case let KingfisherError.responseError(reason: .invalidHTTPStatusCode(response)):
      self.init(status: response.statusCode, detail: error.localizedDescription)
    case let KingfisherError.responseError(reason: .URLSessionError(underlying as URLError)):
      self.init(urlErrorCode: underlying.code, detail: underlying.localizedDescription)
    case KingfisherError.responseError:
      self.init(status: nil, urlErrorCode: nil, isNetwork: true, detail: error.localizedDescription)
    case let error as URLError:
      self.init(urlErrorCode: error.code, detail: error.localizedDescription)
    default:
      self.init(detail: error.localizedDescription)
    }
  }

  /// True for a failure of the request a repeat may not meet: a connection failure, a server
  /// error, 408 or 429. A client error, a URL that cannot be requested, and an image that does
  /// not decode or process are final.
  var isRetryable: Bool {
    guard isNetwork else { return false }
    if urlErrorCode == .badURL || urlErrorCode == .unsupportedURL { return false }
    guard let status, (400 ..< 500).contains(status) else { return true }
    return status == 408 || status == 429
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
