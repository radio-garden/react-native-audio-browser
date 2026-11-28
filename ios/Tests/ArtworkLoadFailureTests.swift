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

  @Test func onlyAConnectionFailureIsRetriedOfTheURLErrors() {
    #expect(urlError(.timedOut).isRetryable)
    #expect(urlError(.networkConnectionLost).isRetryable)
    #expect(urlError(.notConnectedToInternet).isRetryable)
    for code: URLError.Code in [
      .badURL, .unsupportedURL, .cancelled, .secureConnectionFailed, .serverCertificateUntrusted,
      .fileDoesNotExist, .zeroByteResource, .cannotDecodeContentData, .badServerResponse,
    ] {
      #expect(!urlError(code).isRetryable, "\(code)")
    }
  }

  @Test func aServerErrorIsRetried() {
    #expect(status(500).isRetryable)
    #expect(status(503).isRetryable)
  }

  @Test func anyOtherFailureIsFinal() {
    #expect(!ArtworkLoadFailure(NSError(domain: "decode", code: 1)).isRetryable)
    #expect(!ArtworkLoadFailure(KingfisherError.responseError(reason: .URLSessionError(error: NSError(domain: "x", code: 1)))).isRetryable)
  }

  @Test func aConnectionFailureIsToldApartFromAServerError() {
    #expect(urlError(.notConnectedToInternet).isConnectionFailure)
    #expect(!status(503).isConnectionFailure)
  }

  @Test func theDescriptionNeverCarriesTheURL() {
    let url = URL(string: "https://example.com/u/123/a.jpg?token=secret")!
    let response = HTTPURLResponse(url: url, statusCode: 404, httpVersion: nil, headerFields: nil)!
    let refused = ArtworkLoadFailure(KingfisherError.responseError(reason: .invalidHTTPStatusCode(response: response)))
    let invalid = ArtworkLoadFailure(KingfisherError.requestError(reason: .invalidURL(request: URLRequest(url: url))))
    var failing = URLError(.timedOut, userInfo: [NSURLErrorFailingURLErrorKey: url, NSURLErrorFailingURLStringErrorKey: url.absoluteString])
    failing = URLError(failing.code, userInfo: failing.userInfo.merging([NSLocalizedDescriptionKey: "timed out at \(url)"]) { $1 })
    let timedOut = ArtworkLoadFailure(KingfisherError.responseError(reason: .URLSessionError(error: failing)))
    for failure in [refused, invalid, timedOut, ArtworkLoadFailure(failing)] {
      #expect(!failure.description.contains("secret"))
      #expect(!failure.description.contains("/u/123"))
    }
  }

  @Test func aCancelledLoadIsToldApart() {
    #expect(ArtworkLoadFailure(CancellationError()).isCancellation)
    #expect(ArtworkLoadFailure(URLError(.cancelled)).isCancellation)
    #expect(!status(503).isCancellation)
  }

  @Test func theDescriptionNamesTheStatusOrTheURLErrorCode() {
    #expect(status(503).description.hasPrefix("HTTP 503"))
    #expect(urlError(.notConnectedToInternet).description.hasPrefix("URLError -1009"))
  }
}
