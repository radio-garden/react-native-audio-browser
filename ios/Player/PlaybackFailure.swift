import AVFoundation
import Foundation

/// Reads the error reported to JS and the retry decision off the same evidence.
enum PlaybackFailure {
  struct Classification: Equatable {
    /// The narrowest error the evidence supports, else the caller's `fallback`.
    let error: TrackPlayerError.PlaybackError
    let retryable: Bool
  }

  /// Classifies a failure. `httpStatusCode` comes from the item's error log: AVFoundation reports
  /// HTTP failures as opaque CoreMedia errors.
  ///
  /// The two answers rank the evidence differently, on purpose:
  /// - The error: a transport failure first (no response arrived, so a logged status belongs to
  ///   an earlier attempt), then a non-2xx status, then undecodable media.
  /// - Retrying: a non-2xx status first (a retry cannot change what the server refused), then the
  ///   error chain. An unrecognized failure counts as retryable, bounded by the retry budgets, as on
  ///   Android: AVFoundation reports most stream failures opaquely.
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

  /// The HTTP status among an `AVPlayerItem` error log's status codes. Negative OSStatus values
  /// share the field, so only 100–599 counts, and the newest wins: a live stream reconnects, and
  /// older entries describe earlier attempts.
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
    // `as` also bridges a plain NSError in NSURLErrorDomain, AVFoundation's usual form.
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

  /// The stream arrived but cannot be decoded.
  private static let unplayableAVErrorCodes: Set<Int> = [
    AVError.fileFormatNotRecognized.rawValue,
    AVError.failedToParse.rawValue,
    AVError.decoderNotFound.rawValue,
    AVError.decoderTemporarilyUnavailable.rawValue,
    AVError.formatUnsupported.rawValue,
    AVError.contentIsUnavailable.rawValue,
  ]

  // MARK: - Retrying

  /// Retried besides 500–599. Android shares 408/429 but retries only 500/502/503/504 of 5xx.
  private static let retryableHTTPStatusCodes: Set<Int> = [408, 429]

  /// Failures a retry cannot fix. Every unplayable code but the temporary decoder shortage is
  /// here, so a failure reported as unplayable is never also retried. `AVError.unknown` stays out:
  /// it wraps opaque CoreMedia transport failures.
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
    // AVFoundation routinely wraps the real cause (a URLError, a CoreMedia code) in an AVError.
    for candidate in errorChain(error) {
      if let urlError = candidate as? URLError { return urlError.isConnectivity }
      let nsError = candidate as NSError
      if nsError.domain == AVFoundationErrorDomain, fatalAVErrorCodes.contains(nsError.code) {
        return false
      }
    }
    return true
  }

  /// The error and its `NSUnderlyingError` ancestors, outermost first; capped so a cycle cannot spin.
  private static func errorChain(_ error: Error) -> [Error] {
    var chain: [Error] = []
    var current: Error? = error
    while let error = current, chain.count < 8 {
      chain.append(error)
      current = (error as NSError).userInfo[NSUnderlyingErrorKey] as? Error
    }
    return chain
  }
}
