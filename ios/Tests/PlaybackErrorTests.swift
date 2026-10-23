import AVFoundation
import Foundation
import Testing

@testable import AudioBrowserTestable

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
