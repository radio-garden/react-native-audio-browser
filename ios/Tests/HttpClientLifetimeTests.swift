@testable import AudioBrowserTestable
import Foundation
import Testing

/// Answers every request with `{}` and, as headers, the query items it carries.
private final class HeaderEchoProtocol: URLProtocol {
  override class func canInit(with _: URLRequest) -> Bool {
    true
  }

  override class func canonicalRequest(for request: URLRequest) -> URLRequest {
    request
  }

  override func startLoading() {
    let url = request.url!
    let headers = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?
      .reduce(into: [String: String]()) { $0[$1.name] = $1.value }
    let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: headers)!
    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    client?.urlProtocol(self, didLoad: Data("{}".utf8))
    client?.urlProtocolDidFinishLoading(self)
  }

  override func stopLoading() {}
}

@Suite("A JSON response's lifetime")
struct HttpClientLifetimeTests {
  private func fetched(_ headers: [String: String]) async throws -> Fetched<[String: Int]> {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [HeaderEchoProtocol.self]
    let session = URLSession(configuration: configuration)
    defer { session.finishTasksAndInvalidate() }
    var url = URLComponents(string: "https://stub.invalid/page")!
    url.queryItems = headers.map { URLQueryItem(name: $0.key, value: $0.value) }
    let request = HttpClient.HttpRequest(
      url: url.string!, method: .get, headers: nil, body: nil,
      contentType: HttpClient.defaultContentType, userAgent: HttpClient.defaultUserAgent,
    )
    return try await HttpClient(session: session).requestJsonFetched(request, as: [String: Int].self)
  }

  @Test func aPositiveMaxAgeIsTheLifetimeAndAgeWhatHasPassed() async throws {
    let page = try await fetched(["Cache-Control": "max-age=60", "Age": "100"])
    #expect(page.lifetime == 60)
    #expect(page.age == 100)
  }

  @Test(arguments: [nil, "no-store", "no-cache", "max-age=0", "max-age=0, private, must-revalidate", "max-age=soon"])
  func anyOtherResponseHasNoLifetime(cacheControl: String?) async throws {
    #expect(try await fetched(cacheControl.map { ["Cache-Control": $0] } ?? [:]).lifetime == nil)
  }
}
