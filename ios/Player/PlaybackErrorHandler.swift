import AVFoundation
import Foundation
import os.log

/// Abstracts RetryManager for testability.
@MainActor public protocol RetryHandling: AnyObject {
  /// False when the configured policy is `disabled` — every error is then terminal.
  var isEnabled: Bool { get }
  /// Whether the current load has produced audio — selects the retry budget.
  var hasPlayed: Bool { get set }
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

    let failure = PlaybackFailure.classify(error, httpStatusCode: httpStatusCode, fallback: context.fallbackError)
    if retryHandler.isEnabled, failure.retryable {
      onRetryingError?(failure.error)
      pendingRetryTask?.cancel()
      pendingRetryTask = Task { [weak self] in
        guard let self else { return }
        let retried = await retryHandler.attemptRetry(startFromCurrentTime: context.startFromCurrentTime)
        // Cancelled (track change / stop): stay silent — attemptRetry returns
        // false on a cancelled wait, and surfacing that would flip the new
        // track or a deliberate stop to .error. The canceller owns the handle.
        if Task.isCancelled { return }
        if !retried {
          self.onError?(failure.error)
        }
        // Clear the handle: session release is gated on no pending retry.
        pendingRetryTask = nil
      }
      return
    }

    logger.warning("Error not retryable, surfacing \(String(describing: failure.error))")
    onError?(failure.error)
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
}
