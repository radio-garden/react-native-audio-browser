@testable import AudioBrowserTestable
import Foundation
import Testing

/// Answers every request with the `User-Agent` it carries, as `{"userAgent": …}`.
private final class EchoUserAgentProtocol: URLProtocol {
  override class func canInit(with _: URLRequest) -> Bool {
    true
  }

  override class func canonicalRequest(for request: URLRequest) -> URLRequest {
    request
  }

  override func startLoading() {
    let body = ["userAgent": request.value(forHTTPHeaderField: "User-Agent") ?? ""]
    let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!
    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    client?.urlProtocol(self, didLoad: try! JSONEncoder().encode(body))
    client?.urlProtocolDidFinishLoading(self)
  }

  override func stopLoading() {}
}

@Suite("The User-Agent an HTTP request carries")
struct HttpClientUserAgentTests {
  /// The `User-Agent` sent for a request with `headers` and the default `userAgent`.
  private func sentUserAgent(headers: [String: String]) async throws -> String? {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [EchoUserAgentProtocol.self]
    let request = HttpClient.HttpRequest(
      url: "https://stub.invalid/", method: .get, headers: headers, body: nil,
      contentType: HttpClient.defaultContentType, userAgent: HttpClient.defaultUserAgent,
    )
    let session = URLSession(configuration: configuration)
    defer { session.finishTasksAndInvalidate() }
    return try await HttpClient(session: session).requestJson(request, as: [String: String].self)["userAgent"]
  }

  @Test(arguments: ["user-agent", "USER-AGENT"])
  func aHeaderWinsOverTheFieldInAnyCase(name: String) async throws {
    #expect(try await sentUserAgent(headers: [name: "MyApp/1.0"]) == "MyApp/1.0")
  }
}
