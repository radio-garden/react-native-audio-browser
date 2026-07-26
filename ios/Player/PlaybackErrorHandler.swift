import Foundation
import os.log

/// Abstracts RetryManager for testability.
@MainActor public protocol RetryHandling: AnyObject {
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
  private(set) var pendingRetryTask: Task<Void, Never>?
  private let retryHandler: any RetryHandling

  init(retryHandler: any RetryHandling) {
    self.retryHandler = retryHandler
  }

  func handleError(_ error: Error?, context: PlaybackErrorContext) {
    if let error {
      let nsError = error as NSError
      logger.error("[\(String(describing: context))] failure: domain=\(nsError.domain), code=\(nsError.code), localizedDescription=\(error.localizedDescription)")
    } else {
      logger.error("[\(String(describing: context))] failure with nil error")
    }

    if retryHandler.isRetryable(error) {
      pendingRetryTask?.cancel()
      pendingRetryTask = Task { [weak self] in
        guard let self else { return }
        let retried = await retryHandler.attemptRetry(startFromCurrentTime: context.startFromCurrentTime)
        if !retried {
          let classified = PlaybackErrorHandler.classify(error: error, fallback: context.fallbackError)
          self.onError?(classified)
        }
        // Done — clear the handle so intent-gated session release isn't blocked
        // by a completed retry. A cancelled task was already replaced or nilled
        // by its canceller; it must not clobber the successor's handle.
        if !Task.isCancelled { pendingRetryTask = nil }
      }
      return
    }

    logger.warning("Error not retryable, surfacing \(String(describing: context.fallbackError))")
    let classified = PlaybackErrorHandler.classify(error: error, fallback: context.fallbackError)
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

  /// Classifies an error, checking for internet connectivity issues before falling back.
  public static func classify(error: Error?, fallback: TrackPlayerError.PlaybackError) -> TrackPlayerError.PlaybackError {
    let nsError = error as NSError?
    if nsError?.code == URLError.notConnectedToInternet.rawValue {
      return .notConnectedToInternet
    }
    return fallback
  }
}
