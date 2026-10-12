import AVFoundation
import Foundation

/// What a playback failure is reported as, and whether another attempt could fix it: one table,
/// so the error JS receives and the retry decision are read off the same evidence.
enum PlaybackFailure {
  struct Classification: Equatable {
    /// The narrowest error the evidence supports, falling back to the context's error.
    let error: TrackPlayerError.PlaybackError
    let retryable: Bool
  }

  /// Classifies a failure. AVFoundation reports HTTP failures as opaque CoreMedia errors, so
  /// `httpStatusCode` comes from the item's error log, passed in by the caller.
  ///
  /// The two answers rank the evidence differently, on purpose:
  /// - The error: a transport failure first (no response arrived, so a status left in the log
  ///   describes an earlier attempt), then a non-2xx status, then media that cannot be decoded.
  /// - Retrying: a status first (a retry cannot conjure content the server refused), then the
  ///   error and the errors it wraps. An unrecognized failure is transient, bounded by the retry
  ///   budgets, as on Android: AVFoundation reports most stream failures opaquely.
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

  /// Picks the HTTP status out of an `AVPlayerItem` error log's status codes. The codes share
  /// their space with negative OSStatus values, so only in-range values count, and the newest
  /// wins: a live stream reconnects, and older entries describe earlier attempts.
  static func httpStatusCode(fromErrorStatusCodes codes: [Int]) -> Int? {
    codes.last { (100 ... 599).contains($0) }
  }

  // MARK: - The error

  private static func reportedError(
    _ error: Error?,
    httpStatusCode: Int?,
    fallback: TrackPlayerError.PlaybackError,
  ) -> TrackPlayerError.PlaybackError {
    // `as?` bridges a plain NSError in NSURLErrorDomain too, AVFoundation's usual form.
    if let urlError = error as? URLError {
      switch urlError.code {
      case .notConnectedToInternet:
        return .notConnectedToInternet
      case .timedOut, .cannotFindHost, .cannotConnectToHost, .networkConnectionLost, .dnsLookupFailed,
           .secureConnectionFailed:
        return .hostUnreachable
      default:
        break
      }
    }
    if let httpStatusCode, !(200 ... 299).contains(httpStatusCode) {
      return .httpStatus(httpStatusCode)
    }
    if let nsError = error as NSError?, nsError.domain == AVFoundationErrorDomain,
       unplayableAVErrorCodes.contains(nsError.code)
    {
      return .trackWasUnplayable
    }
    return fallback
  }

  /// The stream arrived but cannot be decoded: unknown container, unsupported codec, or no
  /// decoder.
  private static let unplayableAVErrorCodes: Set<Int> = [
    AVError.fileFormatNotRecognized.rawValue,
    AVError.failedToParse.rawValue,
    AVError.decoderNotFound.rawValue,
    AVError.decoderTemporarilyUnavailable.rawValue,
    AVError.formatUnsupported.rawValue,
    AVError.contentIsUnavailable.rawValue,
  ]

  // MARK: - Retrying

  /// Beyond the 500–599 range. Android shares 408/429 but lists only 500/502/503/504 of 5xx.
  private static let retryableHTTPStatusCodes: Set<Int> = [408, 429]

  /// AVFoundation failures a retry cannot fix: the media is unusable, not the transport
  /// unreliable. Every unplayable code but the temporary decoder shortage is here, so a failure
  /// reported as unplayable is never also retried. `AVError.unknown` is absent on purpose: it
  /// is the wrapper around opaque CoreMedia transport failures.
  private static let fatalAVErrorCodes: Set<Int> = unplayableAVErrorCodes
    .subtracting([AVError.decoderTemporarilyUnavailable.rawValue])
    .union([
      AVError.decodeFailed.rawValue,
      AVError.contentIsProtected.rawValue,
      AVError.contentIsNotAuthorized.rawValue,
    ])

  private static func isRetryable(_ error: Error?, httpStatusCode: Int?) -> Bool {
    if let status = httpStatusCode {
      return retryableHTTPStatusCodes.contains(status) || (500 ... 599).contains(status)
    }
    guard let error else { return false }
    // AVFoundation routinely wraps the real cause (a URLError, a CoreMedia code) in an AVError.
    for candidate in errorChain(error) {
      if let urlError = candidate as? URLError {
        switch urlError.code {
        case .timedOut, .networkConnectionLost, .notConnectedToInternet, .cannotConnectToHost, .cannotFindHost,
             .dnsLookupFailed:
          return true
        default:
          return false
        }
      }
      let nsError = candidate as NSError
      if nsError.domain == AVFoundationErrorDomain, fatalAVErrorCodes.contains(nsError.code) {
        return false
      }
    }
    return true
  }

  /// The error and its `NSUnderlyingError` ancestors, outermost first, bounded so a malformed
  /// cycle cannot spin.
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
