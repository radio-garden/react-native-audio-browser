import AVFoundation
import Foundation
import os.log

/// Abstracts RetryManager for testability.
@MainActor public protocol RetryHandling: AnyObject {
  /// False when the configured policy is `disabled` — every error is then terminal.
  var isEnabled: Bool { get }
  /// Whether the current load has produced audio — selects the retry budget.
  var hasPlayed: Bool { get set }
  func isRetryable(_ error: Error?) -> Bool
  func attemptRetry(startFromCurrentTime: Bool) async -> Bool
  func reset()
}

/// Distinguishes playback failures from media-load failures.
/// Each context carries the retry strategy and the fallback error classification.
public enum PlaybackErrorContext {
  /// AVPlayer/AVPlayerItem status failure — retry from current time, fallback `.playbackFailed`.
  case playback
  /// MediaLoader key-value load failure — retry from start, fallback `.failedToLoadKeyValue`.
  case mediaLoad

  var startFromCurrentTime: Bool {
    switch self {
    case .playback: true
    case .mediaLoad: false
    }
  }

  var fallbackError: TrackPlayerError.PlaybackError {
    switch self {
    case .playback: .playbackFailed
    case .mediaLoad: .failedToLoadKeyValue
    }
  }
}

/// Consolidates error retry/classification logic previously duplicated between
/// `handlePlaybackFailure` and `mediaLoaderDidFailWithRetryableError`.
@MainActor public class PlaybackErrorHandler {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "PlaybackErrorHandler")

  var onError: ((TrackPlayerError.PlaybackError) -> Void)?
  /// A failure the retry loop is still working on. Fired when a retry is
  /// scheduled, before its backoff elapses, so UIs can show the cause while
  /// the playback state stays non-terminal. `onError` still fires if the
  /// retries give up.
  var onRetryingError: ((TrackPlayerError.PlaybackError) -> Void)?
  private(set) var pendingRetryTask: Task<Void, Never>?
  private let retryHandler: any RetryHandling

  init(retryHandler: any RetryHandling) {
    self.retryHandler = retryHandler
  }

  func handleError(_ error: Error?, context: PlaybackErrorContext, httpStatusCode: Int? = nil) {
    if let error {
      let nsError = error as NSError
      logger.error("[\(String(describing: context))] failure: domain=\(nsError.domain), code=\(nsError.code), localizedDescription=\(error.localizedDescription)")
    } else {
      logger.error("[\(String(describing: context))] failure with nil error")
    }

    if retryHandler.isEnabled, retryHandler.isRetryable(error) {
      onRetryingError?(PlaybackErrorHandler.classify(
        error: error, fallback: context.fallbackError, httpStatusCode: httpStatusCode,
      ))
      pendingRetryTask?.cancel()
      pendingRetryTask = Task { [weak self] in
        guard let self else { return }
        let retried = await retryHandler.attemptRetry(startFromCurrentTime: context.startFromCurrentTime)
        // Cancelled (track change / stop): stay silent — attemptRetry returns
        // false on a cancelled wait, and surfacing that would flip the new
        // track or a deliberate stop to .error. The canceller owns the handle.
        if Task.isCancelled { return }
        if !retried {
          let classified = PlaybackErrorHandler.classify(error: error, fallback: context.fallbackError, httpStatusCode: httpStatusCode)
          self.onError?(classified)
        }
        // Clear the handle: session release is gated on no pending retry.
        pendingRetryTask = nil
      }
      return
    }

    logger.warning("Error not retryable, surfacing \(String(describing: context.fallbackError))")
    let classified = PlaybackErrorHandler.classify(error: error, fallback: context.fallbackError, httpStatusCode: httpStatusCode)
    onError?(classified)
  }

  func cancelPendingRetry() {
    pendingRetryTask?.cancel()
    pendingRetryTask = nil
  }

  func resetRetry() {
    cancelPendingRetry()
    retryHandler.reset()
  }

  /// A restart from a terminal error begins a new load of the same track:
  /// fresh budgets and unproven playback, so the user's tap yields a visible
  /// first-connect retry window instead of a single silent attempt — the same
  /// behavior as re-selecting the track (ADR 0004).
  func resetForNewLoad() {
    resetRetry()
    retryHandler.hasPlayed = false
  }

  /// Classifies an error into the narrowest case the evidence supports, so JS
  /// receives a real `kind` rather than a context fallback.
  ///
  /// Order matters: a transport failure means we never got a response, so it
  /// outranks any status left in the item's error log. AVFoundation reports
  /// HTTP failures as opaque CoreMedia errors, which is why `httpStatusCode`
  /// is passed in separately by the caller rather than read off `error`.
  public static func classify(
    error: Error?,
    fallback: TrackPlayerError.PlaybackError,
    httpStatusCode: Int? = nil,
  ) -> TrackPlayerError.PlaybackError {
    // `as?` bridges plain `NSError`s in `NSURLErrorDomain` too, which is the
    // form AVFoundation usually hands back.
    if let urlError = error as? URLError {
      switch urlError.code {
      case .notConnectedToInternet:
        return .notConnectedToInternet
      case .timedOut,
           .cannotFindHost,
           .cannotConnectToHost,
           .networkConnectionLost,
           .dnsLookupFailed,
           .secureConnectionFailed:
        return .hostUnreachable
      default:
        break
      }
    }

    if let httpStatusCode, !(200 ... 299).contains(httpStatusCode) {
      return .httpStatus(httpStatusCode)
    }

    if isUnplayableMedia(error) {
      return .trackWasUnplayable
    }

    return fallback
  }

  /// Picks the HTTP status out of an `AVPlayerItem` error log's status codes.
  ///
  /// `errorStatusCode` is a mixed space — negative OSStatus values share it
  /// with HTTP statuses — so only in-range values count, and the newest wins:
  /// a live stream reconnects, and older entries describe earlier attempts.
  public static func httpStatusCode(fromErrorStatusCodes codes: [Int]) -> Int? {
    codes.last { (100 ... 599).contains($0) }
  }

  /// The stream was fetched but cannot be decoded — unknown container,
  /// unsupported codec, or a decoder that refused it.
  private static func isUnplayableMedia(_ error: Error?) -> Bool {
    guard let nsError = error as NSError? else { return false }
    guard nsError.domain == AVFoundationErrorDomain else { return false }
    return [
      AVError.fileFormatNotRecognized,
      AVError.failedToParse,
      AVError.decoderNotFound,
      AVError.decoderTemporarilyUnavailable,
      AVError.formatUnsupported,
      AVError.contentIsUnavailable,
    ].contains { $0.rawValue == nsError.code }
  }
}
