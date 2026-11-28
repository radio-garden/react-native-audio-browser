@testable import AudioBrowserTestable
import Foundation
import Testing

/// Answers every request with `Cache-Control: max-age=60`, and records the cache policy it came
/// with. A protocol of one's own never consults the URL cache, so the policy is what shows it.
private final class MaxAgeProtocol: URLProtocol {
  nonisolated(unsafe) static var cachePolicy: URLRequest.CachePolicy?

  override class func canInit(with _: URLRequest) -> Bool {
    true
  }

  override class func canonicalRequest(for request: URLRequest) -> URLRequest {
    request
  }

  override func startLoading() {
    Self.cachePolicy = request.cachePolicy
    let response = HTTPURLResponse(
      url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Cache-Control": "max-age=60"],
    )!
    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .allowed)
    client?.urlProtocol(self, didLoad: Data("{}".utf8))
    client?.urlProtocolDidFinishLoading(self)
  }

  override func stopLoading() {}
}

@Suite("A JSON response's lifetime")
struct HttpClientLifetimeTests {
  @Test func aResponseGivesTheLifetimeItStatesAndIsNeverReadFromTheUrlCache() async throws {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [MaxAgeProtocol.self]
    let session = URLSession(configuration: configuration)
    defer { session.finishTasksAndInvalidate() }
    let request = HttpClient.HttpRequest(
      url: "https://stub.invalid/page", method: .get, headers: nil, body: nil,
      contentType: HttpClient.defaultContentType, userAgent: HttpClient.defaultUserAgent,
    )

    let (_, lifetime) = try await HttpClient(session: session).requestJsonWithLifetime(request, as: [String: Int].self)

    #expect(lifetime == 60)
    #expect(MaxAgeProtocol.cachePolicy == .reloadIgnoringLocalCacheData)
  }
}
