@testable import AudioBrowserTestable
import Foundation
import Testing

/// Answers every request with `{}` and the `Cache-Control` its `cache-control` query item names.
private final class CacheControlProtocol: URLProtocol {
  override class func canInit(with _: URLRequest) -> Bool {
    true
  }

  override class func canonicalRequest(for request: URLRequest) -> URLRequest {
    request
  }

  override func startLoading() {
    let url = request.url!
    let cacheControl = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?
      .first { $0.name == "cache-control" }?.value
    let response = HTTPURLResponse(
      url: url, statusCode: 200, httpVersion: "HTTP/1.1",
      headerFields: cacheControl.map { ["Cache-Control": $0] },
    )!
    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    client?.urlProtocol(self, didLoad: Data("{}".utf8))
    client?.urlProtocolDidFinishLoading(self)
  }

  override func stopLoading() {}
}

@Suite("A JSON response's lifetime")
struct HttpClientLifetimeTests {
  private func lifetime(cacheControl: String?) async throws -> TimeInterval? {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [CacheControlProtocol.self]
    let session = URLSession(configuration: configuration)
    defer { session.finishTasksAndInvalidate() }
    var url = URLComponents(string: "https://stub.invalid/page")!
    url.queryItems = cacheControl.map { [URLQueryItem(name: "cache-control", value: $0)] }
    let request = HttpClient.HttpRequest(
      url: url.string!, method: .get, headers: nil, body: nil,
      contentType: HttpClient.defaultContentType, userAgent: HttpClient.defaultUserAgent,
    )
    return try await HttpClient(session: session).requestJsonFetched(request, as: [String: Int].self).lifetime
  }

  @Test func aPositiveMaxAgeIsTheLifetime() async throws {
    #expect(try await lifetime(cacheControl: "max-age=60") == 60)
  }

  @Test(arguments: [nil, "no-store", "no-cache", "max-age=0", "max-age=0, private, must-revalidate"])
  func anyOtherResponseHasNoLifetime(cacheControl: String?) async throws {
    #expect(try await lifetime(cacheControl: cacheControl) == nil)
  }

  @Test func theDefaultSessionKeepsNoUrlCache() {
    #expect(HttpClient.uncachedSession.configuration.urlCache == nil)
  }
}
