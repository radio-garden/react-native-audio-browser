import AVFoundation
import Foundation
import Testing

@testable import AudioBrowserTestable

private func reported(
  _ error: Error?, status: Int? = nil, fallback: TrackPlayerError.PlaybackError = .playbackFailed,
) -> TrackPlayerError.PlaybackError {
  PlaybackFailure.classify(error, httpStatusCode: status, fallback: fallback).error
}

private func retryable(_ error: Error?, status: Int? = nil) -> Bool {
  PlaybackFailure.classify(error, httpStatusCode: status, fallback: .playbackFailed).retryable
}

private func avError(_ code: AVError.Code) -> NSError {
  NSError(domain: AVFoundationErrorDomain, code: code.rawValue)
}

@Suite("PlaybackFailure: the reported error")
struct PlaybackFailureReportedErrorTests {
  @Test func noInternet() {
    #expect(reported(URLError(.notConnectedToInternet)) == .notConnectedToInternet)
  }

  /// Both fallbacks pin that `fallback` is plumbed, not hardcoded.
  @Test func anUnclassifiableErrorIsTheFallback() {
    #expect(reported(URLError(.badURL)) == .playbackFailed)
    #expect(reported(URLError(.badURL), fallback: .failedToLoadKeyValue) == .failedToLoadKeyValue)
  }

  /// A TLS failure counts as unreachable: no usable connection was made.
  @Test(arguments: [URLError.Code.timedOut, .cannotFindHost, .secureConnectionFailed])
  func aTransportErrorIsHostUnreachable(code: URLError.Code) {
    #expect(reported(URLError(code)) == .hostUnreachable)
  }

  /// AVFoundation hands back plain NSErrors in NSURLErrorDomain rather than bridged URLErrors.
  @Test func anUnbridgedURLDomainErrorIsClassified() {
    #expect(reported(NSError(domain: NSURLErrorDomain, code: URLError.Code.cannotFindHost.rawValue)) == .hostUnreachable)
  }

  @Test func anHTTPStatusIsReported() {
    #expect(reported(nil, status: 404, fallback: .failedToLoadKeyValue) == .httpStatus(404))
  }

  @Test func aSuccessStatusOrNothingIsTheFallback() {
    #expect(reported(nil, status: 200, fallback: .failedToLoadKeyValue) == .failedToLoadKeyValue)
    #expect(reported(nil) == .playbackFailed)
  }

  /// No response arrived, so a status left in the error log must not outrank the transport error.
  @Test func aTransportErrorOutranksAStatus() {
    #expect(reported(URLError(.notConnectedToInternet), status: 500) == .notConnectedToInternet)
  }

  @Test func anUnclassifiableURLErrorFallsThroughToTheStatus() {
    #expect(reported(URLError(.badURL), status: 500) == .httpStatus(500))
  }

  /// The server's answer beats "the decoder choked", a symptom of being handed an error page.
  @Test func aStatusOutranksUnplayableMedia() {
    #expect(reported(avError(.fileFormatNotRecognized), status: 404) == .httpStatus(404))
  }

  @Test(arguments: [
    AVError.Code.fileFormatNotRecognized, .failedToParse, .decoderNotFound, .decoderTemporarilyUnavailable,
    .formatUnsupported, .contentIsUnavailable,
  ])
  func undecodableMediaIsUnplayable(code: AVError.Code) {
    #expect(reported(avError(code)) == .trackWasUnplayable)
  }

  /// In-domain is not enough: `AVError.unknown` tells us nothing.
  @Test func anUnrelatedAVErrorIsTheFallback() {
    #expect(reported(avError(.unknown)) == .playbackFailed)
  }
}

@Suite("PlaybackFailure: retrying")
struct PlaybackFailureRetryTests {
  /// The status, read off the error log, outranks the opaque error.
  @Test(arguments: [408, 429, 500, 502, 503, 504])
  func aRetryableStatusRetries(status: Int) {
    #expect(retryable(NSError(domain: "CoreMediaErrorDomain", code: -12660), status: status))
  }

  /// A retry cannot conjure content that isn't there, whatever the error says.
  @Test(arguments: [400, 401, 403, 404, 410, 451])
  func aPermanentStatusDoesNotRetry(status: Int) {
    #expect(!retryable(URLError(.timedOut), status: status))
  }

  @Test func aTransientURLErrorRetries() {
    #expect(retryable(URLError(.networkConnectionLost)))
  }

  @Test func aFatalURLErrorDoesNotRetry() {
    #expect(!retryable(URLError(.badURL)))
  }

  /// An opaque CoreMedia failure without a status used to be terminal, so a station that dropped
  /// mid-song never came back on iOS while Android reconnected.
  @Test func anOpaqueCoreMediaErrorRetries() {
    #expect(retryable(NSError(domain: "CoreMediaErrorDomain", code: -12939)))
  }

  /// `AVError.unknown` wraps transport failures, so it stays retryable.
  @Test func avErrorUnknownRetries() {
    #expect(retryable(avError(.unknown)))
  }

  @Test(arguments: [
    AVError.Code.fileFormatNotRecognized, .failedToParse, .decodeFailed, .decoderNotFound, .formatUnsupported,
    .contentIsUnavailable, .contentIsProtected, .contentIsNotAuthorized,
  ])
  func unusableMediaDoesNotRetry(code: AVError.Code) {
    #expect(!retryable(avError(code)))
  }

  /// A failure reported as unplayable is never also retried, except a decoder that is only
  /// temporarily unavailable.
  @Test(arguments: [
    AVError.Code.fileFormatNotRecognized, .failedToParse, .decoderNotFound, .formatUnsupported,
    .contentIsUnavailable,
  ])
  func whatIsReportedUnplayableIsNotRetried(code: AVError.Code) {
    let classification = PlaybackFailure.classify(avError(code), httpStatusCode: nil, fallback: .playbackFailed)
    #expect(classification == .init(error: .trackWasUnplayable, retryable: false))
  }

  @Test func aTemporarilyUnavailableDecoderIsRetried() {
    #expect(retryable(avError(.decoderTemporarilyUnavailable)))
  }

  /// AVFoundation wraps the real cause, so the chain of underlying errors is walked.
  @Test func anUnderlyingURLErrorDecides() {
    let wrappingFatal = NSError(
      domain: AVFoundationErrorDomain, code: AVError.unknown.rawValue,
      userInfo: [NSUnderlyingErrorKey: URLError(.badURL)],
    )
    #expect(!retryable(wrappingFatal))
    let wrappingTransient = NSError(
      domain: AVFoundationErrorDomain, code: AVError.unknown.rawValue,
      userInfo: [NSUnderlyingErrorKey: URLError(.dnsLookupFailed)],
    )
    #expect(retryable(wrappingTransient))
  }

  @Test func nothingToClassifyDoesNotRetry() {
    #expect(!retryable(nil))
  }
}

@Suite("PlaybackFailure.httpStatusCode(fromErrorStatusCodes:)")
struct ErrorLogStatusTests {
  /// `errorStatusCode` carries negative OSStatus values in the same field.
  @Test func skipsNonHttpStatusCodes() {
    #expect(PlaybackFailure.httpStatusCode(fromErrorStatusCodes: [-12939, -1008]) == nil)
    #expect(PlaybackFailure.httpStatusCode(fromErrorStatusCodes: [-12939, 404]) == 404)
  }

  /// A live stream reconnects; older entries describe earlier attempts.
  @Test func prefersTheNewestInRangeStatus() {
    #expect(PlaybackFailure.httpStatusCode(fromErrorStatusCodes: [500, 404]) == 404)
    #expect(PlaybackFailure.httpStatusCode(fromErrorStatusCodes: [404, 500, -12939]) == 500)
  }

  @Test func nilWhenNothingQualifies() {
    #expect(PlaybackFailure.httpStatusCode(fromErrorStatusCodes: []) == nil)
  }
}
