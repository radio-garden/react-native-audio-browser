import Foundation
import Testing

@testable import AudioBrowserTestable

// MARK: - Mock RetryHandler

@MainActor
private final class MockRetryHandler: RetryHandling {
  var retryableErrors: Set<Int> = []
  var attemptRetryResult = false
  var attemptRetryCallCount = 0
  var lastStartFromCurrentTime: Bool?
  var resetCallCount = 0

  func isRetryable(_ error: Error?) -> Bool {
    guard let error else { return false }
    let code = (error as NSError).code
    return retryableErrors.contains(code)
  }

  var attemptRetryDelayNs: UInt64 = 0

  func attemptRetry(startFromCurrentTime: Bool) async -> Bool {
    attemptRetryCallCount += 1
    lastStartFromCurrentTime = startFromCurrentTime
    if attemptRetryDelayNs > 0 {
      try? await Task.sleep(nanoseconds: attemptRetryDelayNs)
    }
    return attemptRetryResult
  }

  func reset() {
    resetCallCount += 1
  }
}

// MARK: - Error Classification

@Suite("PlaybackErrorHandler.classify")
@MainActor
struct ClassifyTests {
  @Test func internetError_returnsNotConnectedToInternet() {
    let error = URLError(.notConnectedToInternet)
    let result = PlaybackErrorHandler.classify(error: error, fallback: .playbackFailed)
    #expect(result == .notConnectedToInternet)
  }

  @Test func internetError_ignoresFallback() {
    let error = URLError(.notConnectedToInternet)
    let result = PlaybackErrorHandler.classify(error: error, fallback: .failedToLoadKeyValue)
    #expect(result == .notConnectedToInternet)
  }

  @Test func otherError_returnsFallback_playbackFailed() {
    let error = URLError(.timedOut)
    let result = PlaybackErrorHandler.classify(error: error, fallback: .playbackFailed)
    #expect(result == .playbackFailed)
  }

  @Test func otherError_returnsFallback_failedToLoadKeyValue() {
    let error = URLError(.timedOut)
    let result = PlaybackErrorHandler.classify(error: error, fallback: .failedToLoadKeyValue)
    #expect(result == .failedToLoadKeyValue)
  }

  @Test func nilError_returnsFallback() {
    let result = PlaybackErrorHandler.classify(error: nil, fallback: .playbackFailed)
    #expect(result == .playbackFailed)
  }
}

// MARK: - PlaybackErrorContext

@Suite("PlaybackErrorContext")
@MainActor
struct ContextTests {
  @Test func playbackContext_startFromCurrentTimeTrue() {
    #expect(PlaybackErrorContext.playback.startFromCurrentTime == true)
  }

  @Test func playbackContext_fallbackIsPlaybackFailed() {
    #expect(PlaybackErrorContext.playback.fallbackError == .playbackFailed)
  }

  @Test func mediaLoadContext_startFromCurrentTimeFalse() {
    #expect(PlaybackErrorContext.mediaLoad.startFromCurrentTime == false)
  }

  @Test func mediaLoadContext_fallbackIsFailedToLoadKeyValue() {
    #expect(PlaybackErrorContext.mediaLoad.fallbackError == .failedToLoadKeyValue)
  }
}

// MARK: - handleError

@Suite("handleError")
@MainActor
struct HandleErrorTests {
  @Test func nonRetryableError_surfacesImmediately() {
    let mock = MockRetryHandler()
    let handler = PlaybackErrorHandler(retryHandler: mock)
    var surfacedError: TrackPlayerError.PlaybackError?
    handler.onError = { surfacedError = $0 }

    let error = URLError(.badURL)
    handler.handleError(error, context: .playback)

    #expect(surfacedError == .playbackFailed)
    #expect(mock.attemptRetryCallCount == 0)
  }

  @Test func nonRetryableInternetError_classifiesCorrectly() {
    let mock = MockRetryHandler()
    // Not in retryableErrors set, so isRetryable returns false
    let handler = PlaybackErrorHandler(retryHandler: mock)
    var surfacedError: TrackPlayerError.PlaybackError?
    handler.onError = { surfacedError = $0 }

    let error = URLError(.notConnectedToInternet)
    handler.handleError(error, context: .mediaLoad)

    // Even though not retryable, classify should detect the internet error
    #expect(surfacedError == .notConnectedToInternet)
  }

  @Test func retryableError_triggersRetry() async {
    let mock = MockRetryHandler()
    mock.retryableErrors = [URLError.Code.timedOut.rawValue]
    mock.attemptRetryResult = true
    let handler = PlaybackErrorHandler(retryHandler: mock)
    var surfacedError: TrackPlayerError.PlaybackError?
    handler.onError = { surfacedError = $0 }

    let error = URLError(.timedOut)
    handler.handleError(error, context: .playback)

    // Wait for the retry task to complete
    await handler.pendingRetryTask?.value

    #expect(mock.attemptRetryCallCount == 1)
    #expect(mock.lastStartFromCurrentTime == true)
    // Retry succeeded, no error surfaced
    #expect(surfacedError == nil)
  }

  /// A cancelled retry must stay silent: track change / stop cancel the task,
  /// but its tail still runs — attemptRetry returns false (wait cancelled) and
  /// the unguarded body surfaced .errorOccurred over the new track / the
  /// deliberate stop.
  @Test func cancelledRetry_staysSilent() async throws {
    let mock = MockRetryHandler()
    mock.retryableErrors = [URLError.Code.timedOut.rawValue]
    mock.attemptRetryResult = false
    mock.attemptRetryDelayNs = 50_000_000
    let handler = PlaybackErrorHandler(retryHandler: mock)
    var surfacedError: TrackPlayerError.PlaybackError?
    handler.onError = { surfacedError = $0 }

    handler.handleError(URLError(.timedOut), context: .playback)
    let task = handler.pendingRetryTask
    handler.cancelPendingRetry()
    await task?.value

    #expect(surfacedError == nil)
  }

  /// A completed retry task must clear its own handle: `evaluateSessionRelease`
  /// gates on `pendingRetryTask == nil`, so a stale completed task blocked
  /// audio-session release for the rest of the track.
  @Test func completedRetryTask_clearsItsHandle() async {
    let mock = MockRetryHandler()
    mock.retryableErrors = [URLError.Code.timedOut.rawValue]
    mock.attemptRetryResult = true
    let handler = PlaybackErrorHandler(retryHandler: mock)

    handler.handleError(URLError(.timedOut), context: .playback)
    #expect(handler.pendingRetryTask != nil)

    await handler.pendingRetryTask?.value

    #expect(handler.pendingRetryTask == nil)
  }

  @Test func retryExhausted_surfacesClassifiedError() async {
    let mock = MockRetryHandler()
    mock.retryableErrors = [URLError.Code.timedOut.rawValue]
    mock.attemptRetryResult = false
    let handler = PlaybackErrorHandler(retryHandler: mock)
    var surfacedError: TrackPlayerError.PlaybackError?
    handler.onError = { surfacedError = $0 }

    let error = URLError(.timedOut)
    handler.handleError(error, context: .mediaLoad)

    await handler.pendingRetryTask?.value

    #expect(mock.attemptRetryCallCount == 1)
    #expect(mock.lastStartFromCurrentTime == false)
    #expect(surfacedError == .failedToLoadKeyValue)
  }

  @Test func retryExhausted_internetError_classifiesCorrectly() async {
    let mock = MockRetryHandler()
    mock.retryableErrors = [URLError.Code.notConnectedToInternet.rawValue]
    mock.attemptRetryResult = false
    let handler = PlaybackErrorHandler(retryHandler: mock)
    var surfacedError: TrackPlayerError.PlaybackError?
    handler.onError = { surfacedError = $0 }

    let error = URLError(.notConnectedToInternet)
    handler.handleError(error, context: .mediaLoad)

    await handler.pendingRetryTask?.value

    // This is the bug fix: previously mediaLoad non-retryable path
    // would always return .failedToLoadKeyValue
    #expect(surfacedError == .notConnectedToInternet)
  }
}

// MARK: - cancelPendingRetry

@Suite("cancelPendingRetry")
@MainActor
struct CancelTests {
  @Test func cancelClearsPendingTask() async {
    let mock = MockRetryHandler()
    mock.retryableErrors = [URLError.Code.timedOut.rawValue]
    mock.attemptRetryResult = true
    let handler = PlaybackErrorHandler(retryHandler: mock)

    let error = URLError(.timedOut)
    handler.handleError(error, context: .playback)

    #expect(handler.pendingRetryTask != nil)
    handler.cancelPendingRetry()
    #expect(handler.pendingRetryTask == nil)
  }
}

// MARK: - resetRetry

@Suite("resetRetry")
@MainActor
struct ErrorHandlerResetTests {
  @Test func resetCancelsAndResetsHandler() {
    let mock = MockRetryHandler()
    let handler = PlaybackErrorHandler(retryHandler: mock)

    handler.resetRetry()

    #expect(handler.pendingRetryTask == nil)
    #expect(mock.resetCallCount == 1)
  }
}

// MARK: - PlaybackError Nitro Code Mapping

@Suite("PlaybackError.toNitroError")
@MainActor
struct PlaybackErrorNitroCodeTests {
  @Test func playbackStalled_mapsToNitroCode() {
    #expect(TrackPlayerError.PlaybackError.playbackStalled.toNitroError().code == "playback-stalled")
  }
}
