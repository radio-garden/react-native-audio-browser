import Foundation
import Kingfisher
import Testing

@testable import AudioBrowserTestable

@Suite("ArtworkLoadFailure")
struct ArtworkLoadFailureTests {
  private func status(_ code: Int) -> ArtworkLoadFailure {
    let response = HTTPURLResponse(url: URL(string: "https://example.com/a.jpg")!, statusCode: code, httpVersion: nil, headerFields: nil)!
    return ArtworkLoadFailure(KingfisherError.responseError(reason: .invalidHTTPStatusCode(response: response)))
  }

  private func urlError(_ code: URLError.Code) -> ArtworkLoadFailure {
    ArtworkLoadFailure(KingfisherError.responseError(reason: .URLSessionError(error: URLError(code))))
  }

  @Test func readsTheHTTPStatusOfARefusedResponse() {
    #expect(status(503).status == 503)
    #expect(status(503).urlErrorCode == nil)
  }

  @Test func readsTheURLErrorCodeOfAFailedRequest() {
    #expect(urlError(.notConnectedToInternet).urlErrorCode == .notConnectedToInternet)
    #expect(urlError(.notConnectedToInternet).status == nil)
  }

  @Test func aURLErrorOutsideKingfisherIsReadToo() {
    #expect(ArtworkLoadFailure(URLError(.timedOut)).urlErrorCode == .timedOut)
  }

  @Test func aClientErrorIsFinalButATimeoutOrThrottleIsNot() {
    #expect(!status(404).isRetryable)
    #expect(!status(403).isRetryable)
    #expect(status(408).isRetryable)
    #expect(status(429).isRetryable)
  }

  @Test func aURLThatCannotBeRequestedIsFinal() {
    #expect(!ArtworkLoadFailure(URLError(.badURL)).isRetryable)
    #expect(!ArtworkLoadFailure(URLError(.unsupportedURL)).isRetryable)
  }

  @Test func aServerErrorOrAConnectionFailureIsRetried() {
    #expect(status(500).isRetryable)
    #expect(status(503).isRetryable)
    #expect(urlError(.networkConnectionLost).isRetryable)
    #expect(ArtworkLoadFailure(URLError(.badServerResponse)).isRetryable)
  }

  @Test func anImageThatDoesNotDecodeIsFinal() {
    #expect(!ArtworkLoadFailure(NSError(domain: "decode", code: 1)).isRetryable)
  }

  @Test func theDescriptionNamesTheStatusOrTheURLErrorCode() {
    #expect(status(503).description.hasPrefix("HTTP 503"))
    #expect(urlError(.notConnectedToInternet).description.hasPrefix("URLError -1009"))
  }
}
