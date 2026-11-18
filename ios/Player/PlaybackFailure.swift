import AVFoundation
import Foundation

enum PlaybackFailure {
  struct Classification: Equatable {
    let error: TrackPlayerError.PlaybackError
    let retryable: Bool
  }

  /// What a failure reports and whether the retry loop tries again. The reported error is the first
  /// that applies: a connectivity `URLError` in the underlying-error chain, a non-2xx
  /// `httpStatusCode`, an unplayable AVFoundation code, else `fallback`. Retrying ranks a non-2xx
  /// status first; a failure it cannot recognise counts as retryable, as on Android.
  static func classify(
    _ error: Error?,
    httpStatusCode: Int?,
    fallback: TrackPlayerError.PlaybackError,
  ) -> Classification {
    Classification(
      error: reportedError(error, httpStatusCode: httpStatusCode, fallback: fallback),
      retryable: isRetryable(error, httpStatusCode: httpStatusCode),
    )
  }

  /// The newest HTTP status in an item's error log, or nil. Negative OSStatus codes share the
  /// field, and older entries belong to earlier attempts.
  static func httpStatusCode(fromErrorStatusCodes codes: [Int]) -> Int? {
    codes.last { (100 ... 599).contains($0) }
  }

  // MARK: - The error

  private static func reportedError(
    _ error: Error?,
    httpStatusCode: Int?,
    fallback: TrackPlayerError.PlaybackError,
  ) -> TrackPlayerError.PlaybackError {
    let chain = error.map(errorChain) ?? []
    // `as` also matches a plain NSError in NSURLErrorDomain.
    for case let urlError as URLError in chain {
      switch urlError.code {
      case .notConnectedToInternet:
        return .notConnectedToInternet
      case .timedOut, .cannotFindHost, .cannotConnectToHost, .networkConnectionLost, .dnsLookupFailed,
           .secureConnectionFailed:
        return .hostUnreachable
      default:
        continue
      }
    }
    if let httpStatusCode, !(200 ... 299).contains(httpStatusCode) {
      return .httpStatus(httpStatusCode)
    }
    let unplayable = chain.contains {
      let nsError = $0 as NSError
      return nsError.domain == AVFoundationErrorDomain && unplayableAVErrorCodes.contains(nsError.code)
    }
    return unplayable ? .trackWasUnplayable : fallback
  }

  private static let unplayableAVErrorCodes: Set<Int> = [
    AVError.fileFormatNotRecognized.rawValue,
    AVError.failedToParse.rawValue,
    AVError.decoderNotFound.rawValue,
    AVError.decoderTemporarilyUnavailable.rawValue,
    AVError.formatUnsupported.rawValue,
    AVError.contentIsUnavailable.rawValue,
  ]

  // MARK: - Retrying

  /// Retried besides 500–599. Android retries only 500, 502, 503 and 504 of the 5xx.
  private static let retryableHTTPStatusCodes: Set<Int> = [408, 429]

  // A temporary decoder shortage is reported as unplayable but still retried (ADR 0012).
  // `AVError.unknown` stays out: it wraps opaque transport failures.
  private static let fatalAVErrorCodes: Set<Int> = unplayableAVErrorCodes
    .subtracting([AVError.decoderTemporarilyUnavailable.rawValue])
    .union([
      AVError.decodeFailed.rawValue,
      AVError.contentIsProtected.rawValue,
      AVError.contentIsNotAuthorized.rawValue,
    ])

  private static func isRetryable(_ error: Error?, httpStatusCode: Int?) -> Bool {
    if let status = httpStatusCode, !(200 ... 299).contains(status) {
      return retryableHTTPStatusCodes.contains(status) || (500 ... 599).contains(status)
    }
    guard let error else { return false }
    for candidate in errorChain(error) {
      if let urlError = candidate as? URLError { return urlError.isConnectivity }
      let nsError = candidate as NSError
      if nsError.domain == AVFoundationErrorDomain, fatalAVErrorCodes.contains(nsError.code) {
        return false
      }
    }
    return true
  }

  private static func errorChain(_ error: Error) -> [Error] {
    var chain: [Error] = []
    var current: Error? = error
    // Capped: an underlying-error chain can cycle.
    while let error = current, chain.count < 8 {
      chain.append(error)
      current = (error as NSError).userInfo[NSUnderlyingErrorKey] as? Error
    }
    return chain
  }
}
