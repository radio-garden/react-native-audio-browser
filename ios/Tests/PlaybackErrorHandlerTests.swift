import AVFoundation
import Foundation
import Testing

@testable import AudioBrowserTestable

// MARK: - Mock RetryHandler

@MainActor
private final class MockRetryHandler: RetryHandling {
  var isEnabled = true
  var hasPlayed = false
  var retryableErrors: Set<Int> = []
  var attemptRetryResult = false
  var attemptRetryCallCount = 0
  var lastStartFromCurrentTime: Bool?
  var lastHTTPStatusCode: Int??
  var resetCallCount = 0

  func isRetryable(_ error: Error?, httpStatusCode: Int?) -> Bool {
    lastHTTPStatusCode = .some(httpStatusCode)
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

  /// `.badURL` rather than `.timedOut`: timeouts now classify as
  /// `.hostUnreachable`, so they no longer exercise the fallback path. Both
  /// fallbacks are asserted to pin that `fallback` is plumbed, not hardcoded.
  @Test func unclassifiableError_returnsFallback() {
    #expect(
      PlaybackErrorHandler.classify(error: URLError(.badURL), fallback: .playbackFailed)
        == .playbackFailed,
    )
    #expect(
      PlaybackErrorHandler.classify(error: URLError(.badURL), fallback: .failedToLoadKeyValue)
        == .failedToLoadKeyValue,
    )
  }

  @Test(arguments: [
    URLError.Code.timedOut,
    .cannotFindHost,
    // A TLS failure counts as unreachable: we never got a usable connection.
    .secureConnectionFailed,
  ])
  func transportError_returnsHostUnreachable(code: URLError.Code) {
    let result = PlaybackErrorHandler.classify(error: URLError(code), fallback: .playbackFailed)
    #expect(result == .hostUnreachable)
  }

  /// AVFoundation hands back plain `NSError`s rather than bridged `URLError`s.
  /// `as?` covers this, but only for `NSURLErrorDomain` — worth pinning, since
  /// losing it would silently downgrade every real timeout to the fallback.
  @Test func unbridgedUrlDomainError_isClassified() {
    let error = NSError(domain: NSURLErrorDomain, code: URLError.Code.cannotFindHost.rawValue)
    let result = PlaybackErrorHandler.classify(error: error, fallback: .playbackFailed)
    #expect(result == .hostUnreachable)
  }

  @Test func httpStatus_isSurfaced() {
    let result = PlaybackErrorHandler.classify(
      error: nil,
      fallback: .failedToLoadKeyValue,
      httpStatusCode: 404,
    )
    #expect(result == .httpStatus(404))
  }

  /// Also covers the nil-error path: with no error and no usable status, the
  /// context fallback is all that is left.
  @Test func successStatus_returnsFallback() {
    #expect(
      PlaybackErrorHandler.classify(
        error: nil,
        fallback: .failedToLoadKeyValue,
        httpStatusCode: 200,
      ) == .failedToLoadKeyValue,
    )
    #expect(
      PlaybackErrorHandler.classify(error: nil, fallback: .playbackFailed) == .playbackFailed,
    )
  }

  /// A transport failure means no response arrived, so a status left over in
  /// the item's error log must not outrank it.
  @Test func transportError_outranksHttpStatus() {
    let result = PlaybackErrorHandler.classify(
      error: URLError(.notConnectedToInternet),
      fallback: .playbackFailed,
      httpStatusCode: 500,
    )
    #expect(result == .notConnectedToInternet)
  }

  /// A `URLError` we can't classify must fall *through* to the status check,
  /// not short-circuit to the fallback.
  @Test func unclassifiableTransportError_stillUsesHttpStatus() {
    let result = PlaybackErrorHandler.classify(
      error: URLError(.badURL),
      fallback: .playbackFailed,
      httpStatusCode: 500,
    )
    #expect(result == .httpStatus(500))
  }

  /// The server's own answer is better evidence than "the decoder choked",
  /// which is a downstream symptom of being handed an error page.
  @Test func httpStatus_outranksUnplayableMedia() {
    let error = NSError(
      domain: AVFoundationErrorDomain,
      code: AVError.fileFormatNotRecognized.rawValue,
    )
    let result = PlaybackErrorHandler.classify(
      error: error,
      fallback: .playbackFailed,
      httpStatusCode: 404,
    )
    #expect(result == .httpStatus(404))
  }

  @Test func unplayableMedia_returnsTrackWasUnplayable() {
    let error = NSError(
      domain: AVFoundationErrorDomain,
      code: AVError.fileFormatNotRecognized.rawValue,
    )
    let result = PlaybackErrorHandler.classify(error: error, fallback: .playbackFailed)
    #expect(result == .trackWasUnplayable)
  }

  /// Being in `AVFoundationErrorDomain` is not enough — `AVError.unknown` is
  /// in-domain but tells us nothing.
  @Test func unrelatedAVError_returnsFallback() {
    let error = NSError(domain: AVFoundationErrorDomain, code: AVError.unknown.rawValue)
    let result = PlaybackErrorHandler.classify(error: error, fallback: .playbackFailed)
    #expect(result == .playbackFailed)
  }
}

// MARK: - Error Log Status Extraction

/// The sole source of every `.httpStatus` classification in production.
@Suite("PlaybackErrorHandler.httpStatusCode(fromErrorStatusCodes:)")
@MainActor
struct ErrorLogStatusTests {
  /// `errorStatusCode` carries negative OSStatus values in the same field.
  @Test func skipsNonHttpStatusCodes() {
    #expect(PlaybackErrorHandler.httpStatusCode(fromErrorStatusCodes: [-12939, -1008]) == nil)
    #expect(PlaybackErrorHandler.httpStatusCode(fromErrorStatusCodes: [-12939, 404]) == 404)
  }

  /// A live stream reconnects; older entries describe attempts that are no
  /// longer why we are failing.
  @Test func prefersTheNewestInRangeStatus() {
    #expect(PlaybackErrorHandler.httpStatusCode(fromErrorStatusCodes: [500, 404]) == 404)
    #expect(PlaybackErrorHandler.httpStatusCode(fromErrorStatusCodes: [404, 500, -12939]) == 500)
  }

  @Test func nilWhenNothingQualifies() {
    #expect(PlaybackErrorHandler.httpStatusCode(fromErrorStatusCodes: []) == nil)
  }
}

// MARK: - Cross-platform Kind Mapping

/// Only the HTTP table is tested: the `kind` switch is exhaustive with no
/// `default`, so the compiler already forces every new case to be classified
/// deliberately, and asserting the other arms would just retype the switch.
/// The status ranges are different — `404, 410` must precede `400...499`.
@Suite("PlaybackError.kind")
@MainActor
struct PlaybackErrorKindTests {
  @Test func httpStatus_mapsToKind() {
    // Not parameterized: the generated Nitro enum is not Sendable, so it
    // cannot cross a `@Test(arguments:)` isolation boundary.
    let expectations: [(Int, PlaybackErrorKind)] = [
      // Ordering: these must not be swallowed by the 400...499 arm.
      (404, .notFound),
      (410, .notFound),
      // Range edges.
      (400, .rejected),
      (499, .rejected),
      (500, .serverError),
      (599, .serverError),
      (302, .unknown),
      (600, .unknown),
    ]
    for (status, expected) in expectations {
      #expect(
        TrackPlayerError.PlaybackError.httpStatus(status).kind == expected,
        "HTTP \(status)",
      )
    }
  }
}

// `PlaybackErrorContext`'s two properties are asserted through `handleError`
// below — `lastStartFromCurrentTime` and the surfaced fallback — rather than
// read off the enum directly, so the tests exercise the path a caller uses.

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

  /// Also pins that a completed retry task clears its own handle:
  /// `evaluateSessionRelease` gates on `pendingRetryTask == nil`, so a stale
  /// completed task blocked audio-session release for the rest of the track.
  @Test func retryableError_triggersRetry() async {
    let mock = MockRetryHandler()
    mock.retryableErrors = [URLError.Code.timedOut.rawValue]
    mock.attemptRetryResult = true
    let handler = PlaybackErrorHandler(retryHandler: mock)
    var surfaced: [TrackPlayerError.PlaybackError] = []
    handler.onError = { surfaced.append($0) }

    let error = URLError(.timedOut)
    handler.handleError(error, context: .playback)
    #expect(handler.pendingRetryTask != nil)

    // Wait for the retry task to complete
    await handler.pendingRetryTask?.value

    #expect(mock.attemptRetryCallCount == 1)
    #expect(mock.lastStartFromCurrentTime == true)
    // Retry succeeded, no error surfaced
    #expect(surfaced.isEmpty)
    #expect(handler.pendingRetryTask == nil)
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
    var surfaced: [TrackPlayerError.PlaybackError] = []
    handler.onError = { surfaced.append($0) }

    handler.handleError(URLError(.timedOut), context: .playback)
    let task = handler.pendingRetryTask
    handler.cancelPendingRetry()
    #expect(handler.pendingRetryTask == nil)
    await task?.value

    #expect(surfaced.isEmpty)
  }

  /// A second failure while a retry is in flight cancels the first. The
  /// superseded task's tail must not surface an error, and must not null out
  /// the handle belonging to the retry that replaced it.
  @Test func supersedingError_cancelsTheInFlightRetry() async {
    let mock = MockRetryHandler()
    mock.retryableErrors = [URLError.Code.timedOut.rawValue]
    mock.attemptRetryResult = true
    mock.attemptRetryDelayNs = 20_000_000
    let handler = PlaybackErrorHandler(retryHandler: mock)
    var surfaced: [TrackPlayerError.PlaybackError] = []
    handler.onError = { surfaced.append($0) }

    handler.handleError(URLError(.timedOut), context: .playback)
    let first = handler.pendingRetryTask
    handler.handleError(URLError(.timedOut), context: .playback)
    let second = handler.pendingRetryTask

    await first?.value
    await second?.value

    #expect(mock.attemptRetryCallCount == 2)
    #expect(surfaced.isEmpty)
  }

  /// The advisory report must precede the retry outcome: it exists so UIs can
  /// show the cause while the backoff runs, and it must carry the same
  /// classification the terminal path would produce.
  @Test func retryableError_reportsRetryingErrorBeforeTheRetryResolves() async {
    let mock = MockRetryHandler()
    mock.retryableErrors = [URLError.Code.cannotConnectToHost.rawValue]
    mock.attemptRetryResult = true
    let handler = PlaybackErrorHandler(retryHandler: mock)
    var retrying: [TrackPlayerError.PlaybackError] = []
    var surfaced: [TrackPlayerError.PlaybackError] = []
    handler.onRetryingError = { retrying.append($0) }
    handler.onError = { surfaced.append($0) }

    handler.handleError(URLError(.cannotConnectToHost), context: .mediaLoad)

    // Reported synchronously, before the retry task has run at all.
    #expect(retrying == [.hostUnreachable])
    #expect(mock.attemptRetryCallCount == 0)

    await handler.pendingRetryTask?.value
    // A successful retry surfaces nothing further.
    #expect(surfaced.isEmpty)
  }

  /// Exhaustion after an advisory report still surfaces the terminal error —
  /// the advisory does not consume it.
  @Test func retryExhausted_reportsRetryingThenTerminal() async {
    let mock = MockRetryHandler()
    mock.retryableErrors = [URLError.Code.cannotConnectToHost.rawValue]
    mock.attemptRetryResult = false
    let handler = PlaybackErrorHandler(retryHandler: mock)
    var retrying: [TrackPlayerError.PlaybackError] = []
    var surfaced: [TrackPlayerError.PlaybackError] = []
    handler.onRetryingError = { retrying.append($0) }
    handler.onError = { surfaced.append($0) }

    handler.handleError(URLError(.cannotConnectToHost), context: .mediaLoad)
    await handler.pendingRetryTask?.value

    #expect(retrying == [.hostUnreachable])
    #expect(surfaced == [.hostUnreachable])
  }

  /// With the policy disabled every error is terminal: no advisory, no retry
  /// task — the error surfaces synchronously even for a retryable class.
  @Test func retryDisabled_skipsTheRetryingReport() {
    let mock = MockRetryHandler()
    mock.isEnabled = false
    mock.retryableErrors = [URLError.Code.cannotConnectToHost.rawValue]
    let handler = PlaybackErrorHandler(retryHandler: mock)
    var retrying: [TrackPlayerError.PlaybackError] = []
    var surfacedError: TrackPlayerError.PlaybackError?
    handler.onRetryingError = { retrying.append($0) }
    handler.onError = { surfacedError = $0 }

    handler.handleError(URLError(.cannotConnectToHost), context: .mediaLoad)

    #expect(retrying.isEmpty)
    #expect(handler.pendingRetryTask == nil)
    #expect(surfacedError == .hostUnreachable)
    #expect(mock.attemptRetryCallCount == 0)
  }

  /// The status reaches `classify` on the non-retryable path. Both call sites
  /// forward it separately; dropping either one is invisible otherwise.
  @Test func nonRetryable_forwardsHttpStatusCode() {
    let mock = MockRetryHandler()
    let handler = PlaybackErrorHandler(retryHandler: mock)
    var surfacedError: TrackPlayerError.PlaybackError?
    handler.onError = { surfacedError = $0 }

    handler.handleError(nil, context: .playback, httpStatusCode: 404)

    #expect(surfacedError == .httpStatus(404))
  }

  /// The same, on the retry-exhausted path.
  @Test func retryExhausted_forwardsHttpStatusCode() async {
    let mock = MockRetryHandler()
    mock.retryableErrors = [URLError.Code.badURL.rawValue]
    mock.attemptRetryResult = false
    let handler = PlaybackErrorHandler(retryHandler: mock)
    var surfacedError: TrackPlayerError.PlaybackError?
    handler.onError = { surfacedError = $0 }

    handler.handleError(URLError(.badURL), context: .playback, httpStatusCode: 503)
    await handler.pendingRetryTask?.value

    #expect(surfacedError == .httpStatus(503))
  }

  /// `.badURL` keeps this focused on the retry-exhaustion path: it is
  /// unclassifiable, so the surfaced error is the context fallback and not a
  /// classification the error itself carried.
  @Test func retryExhausted_surfacesClassifiedError() async {
    let mock = MockRetryHandler()
    mock.retryableErrors = [URLError.Code.badURL.rawValue]
    mock.attemptRetryResult = false
    let handler = PlaybackErrorHandler(retryHandler: mock)
    var surfacedError: TrackPlayerError.PlaybackError?
    handler.onError = { surfacedError = $0 }

    let error = URLError(.badURL)
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

// `cancelPendingRetry` is asserted through `cancelledRetry_staysSilent`, which
// exercises the cancel *and* the silence it has to buy.

// MARK: - resetRetry

@Suite("resetRetry")
@MainActor
struct ErrorHandlerResetTests {
  /// A restart from terminal error must reset budgets AND mark playback
  /// unproven — keeping `hasPlayed` would grant the tap a 2-minute recovery
  /// window at the one moment the listener is definitely watching.
  @Test func resetForNewLoad_clearsBudgetsAndHasPlayed() {
    let mock = MockRetryHandler()
    mock.hasPlayed = true
    let handler = PlaybackErrorHandler(retryHandler: mock)

    handler.resetForNewLoad()

    #expect(mock.resetCallCount == 1)
    #expect(mock.hasPlayed == false)
  }

  /// A retry is started first: with no pending task the `pendingRetryTask ==
  /// nil` assertion passes even if `resetRetry` drops its `cancelPendingRetry`.
  @Test func resetCancelsAndResetsHandler() async {
    let mock = MockRetryHandler()
    mock.retryableErrors = [URLError.Code.timedOut.rawValue]
    mock.attemptRetryResult = true
    mock.attemptRetryDelayNs = 20_000_000
    let handler = PlaybackErrorHandler(retryHandler: mock)

    handler.handleError(URLError(.timedOut), context: .playback)
    let task = handler.pendingRetryTask
    #expect(task != nil)

    handler.resetRetry()

    #expect(handler.pendingRetryTask == nil)
    #expect(mock.resetCallCount == 1)
    await task?.value
  }
}

// MARK: - Nitro Payload

@Suite("PlaybackError.toNitroError")
@MainActor
struct PlaybackErrorNitroCodeTests {
  @Test func httpStatus_producesTheFullPayload() {
    let payload = TrackPlayerError.PlaybackError.httpStatus(404).toNitroError()
    #expect(payload.kind == .notFound)
    #expect(payload.code == "http-status")
    #expect(payload.message == "Server responded with HTTP 404")
    #expect(payload.statusCode == 404)
  }

  @Test func nonHttpError_carriesNoStatusCode() {
    #expect(TrackPlayerError.PlaybackError.playbackStalled.toNitroError().statusCode == nil)
  }

  /// `retrying` is nil — not false — on a terminal payload, so JS consumers
  /// see the field only while it means something.
  @Test func retryingFlag_isNilUnlessSet() {
    #expect(TrackPlayerError.PlaybackError.hostUnreachable.toNitroError().retrying == nil)
    #expect(
      TrackPlayerError.PlaybackError.hostUnreachable.toNitroError(retrying: true).retrying == true,
    )
  }

  /// `code` is an untyped wire contract: nothing on either side of the bridge
  /// catches a typo, and telemetry groups issues by it. Worth an exhaustive
  /// table even though `kind` is what the UI branches on.
  @Test func codes_areStable() {
    let expectations: [(TrackPlayerError.PlaybackError, String)] = [
      (.failedToLoadKeyValue, "failed-to-load"),
      (.invalidSourceUrl("nil"), "invalid-source-url"),
      (.notConnectedToInternet, "not-connected-to-internet"),
      (.hostUnreachable, "host-unreachable"),
      (.httpStatus(500), "http-status"),
      (.playbackFailed, "playback-failed"),
      (.trackWasUnplayable, "track-unplayable"),
      (.playbackStalled, "playback-stalled"),
    ]
    for (error, expected) in expectations {
      #expect(error.toNitroError().code == expected, "\(error)")
    }
  }
}
