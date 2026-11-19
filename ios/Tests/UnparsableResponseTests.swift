@testable import AudioBrowserTestable
import Foundation
import Testing

/// Answers every request from its own URL: the status is the path, the body the `body` query item.
private final class StubResponseProtocol: URLProtocol {
  static func url(status: Int, body: String) -> String {
    var components = URLComponents()
    components.scheme = "https"
    components.host = "stub.invalid"
    components.path = "/\(status)"
    components.queryItems = [URLQueryItem(name: "body", value: body)]
    return components.string!
  }

  override class func canInit(with _: URLRequest) -> Bool {
    true
  }

  override class func canonicalRequest(for request: URLRequest) -> URLRequest {
    request
  }

  override func startLoading() {
    let url = request.url!
    let body = URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems!.first { $0.name == "body" }!.value!
    let response = HTTPURLResponse(
      url: url, statusCode: Int(url.lastPathComponent)!, httpVersion: "HTTP/1.1", headerFields: nil,
    )!
    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    client?.urlProtocol(self, didLoad: Data(body.utf8))
    client?.urlProtocolDidFinishLoading(self)
  }

  override func stopLoading() {}
}

@Suite("A 2xx response that does not parse")
struct UnparsableResponseTests {
  /// The navigation error of requesting `body`, served with `status`, as `type`.
  private func navigationError(status: Int, body: String, as type: (some Decodable).Type) async -> NavigationError? {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [StubResponseProtocol.self]
    let request = HttpClient.HttpRequest(
      url: StubResponseProtocol.url(status: status, body: body), method: .get, headers: nil, body: nil,
      contentType: HttpClient.defaultContentType, userAgent: HttpClient.defaultUserAgent,
    )
    let session = URLSession(configuration: configuration)
    defer { session.finishTasksAndInvalidate() }
    do {
      _ = try await HttpClient(session: session).requestJson(request, as: type)
      return nil
    } catch {
      return NavigationError.from(error)
    }
  }

  @Test(arguments: [
    ("<html><body>Maintenance</body></html>", "not valid JSON"),
    (#"{"path": "/library"}"#, "Missing required key 'title'"),
    (#"{"path": "/library", "title": "Library", "duration": "12"}"#, "Type mismatch for Double at path: duration"),
  ])
  func aPageIsAnHttpErrorWithItsStatus(body: String, reason: String) async throws {
    let result = await navigationError(status: 200, body: body, as: JsonBrowsePage.self)
    let error = try #require(result)

    #expect(error.code == .httpError)
    #expect(error.statusCode == 200)
    #expect(error.statusCodeSuccess == true)
    #expect(error.message.contains(reason))
  }

  @Test
  func searchResultsAreAnHttpErrorWithTheirStatus() async throws {
    let result = await navigationError(status: 201, body: #"{"results": []}"#, as: [JsonTrack].self)
    let error = try #require(result)

    #expect(error.code == .httpError)
    #expect(error.statusCode == 201)
    #expect(error.statusCodeSuccess == true)
    #expect(error.message.contains("Type mismatch"))
  }

  @Test
  func itsDefaultTitleIsServerError() async throws {
    let result = await navigationError(status: 200, body: "<html></html>", as: JsonResolvedTrack.self)
    let error = try #require(result)

    #expect(error.defaultFormatted().title == "Server Error")
  }
}
