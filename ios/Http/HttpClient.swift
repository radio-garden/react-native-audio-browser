import Foundation
import os.log

/// URLSession-based HTTP client for API requests.
final class HttpClient: @unchecked Sendable {
  static let defaultUserAgent = "react-native-audio-browser"
  static let defaultContentType = "application/json"
  private static let timeoutSeconds: TimeInterval = 30

  private let session: URLSession
  private let logger = Logger(subsystem: "com.audiobrowser", category: "HttpClient")

  /// HTTP request configuration (matches Kotlin HttpClient.HttpRequest).
  struct HttpRequest {
    let url: String
    let method: HttpMethod
    let headers: [String: String]?
    let body: String?
    let contentType: String
    let userAgent: String
  }

  /// A non-2xx response (matches Kotlin HttpClient.HttpException).
  struct HttpException: LocalizedError {
    let code: Int
    let responseBody: String

    /// The API's `{ "error": "message" }`, else the localized HTTP status.
    var errorDescription: String? {
      if let data = responseBody.data(using: .utf8),
         let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
         let message = json["error"] as? String
      {
        return message
      }
      return HTTPURLResponse.localizedString(forStatusCode: code)
    }
  }

  enum HttpClientError: LocalizedError {
    case invalidUrl(String)
    case unsupportedMethod(HttpMethod)
    case notHttp
    case undecodable(String)

    var errorDescription: String? {
      switch self {
      case let .invalidUrl(url): "Invalid URL: \(url)"
      case let .unsupportedMethod(method): "Unsupported HTTP method: \(method.stringValue)"
      case .notHttp: "Invalid response type"
      case let .undecodable(reason): reason
      }
    }
  }

  init(session: URLSession = .shared) {
    self.session = session
  }

  /// Sends `httpRequest` and returns the status code and body, whatever the status.
  private func request(_ httpRequest: HttpRequest) async throws -> (code: Int, data: Data) {
    guard let url = URL(string: httpRequest.url) else { throw HttpClientError.invalidUrl(httpRequest.url) }

    var urlRequest = URLRequest(url: url)
    urlRequest.timeoutInterval = Self.timeoutSeconds
    httpRequest.headers?.forEach { name, value in
      urlRequest.setValue(value, forHTTPHeaderField: name)
    }
    // A `User-Agent` header wins over `userAgent`, as in the media and artwork paths.
    if httpRequest.headers?["User-Agent"] == nil {
      urlRequest.setValue(httpRequest.userAgent, forHTTPHeaderField: "User-Agent")
    }

    urlRequest.httpMethod = httpRequest.method.stringValue
    switch httpRequest.method {
    case .get:
      break
    case .post, .put, .patch:
      urlRequest.setValue(httpRequest.contentType, forHTTPHeaderField: "Content-Type")
      urlRequest.httpBody = httpRequest.body?.data(using: .utf8)
    case .delete:
      if let body = httpRequest.body {
        urlRequest.setValue(httpRequest.contentType, forHTTPHeaderField: "Content-Type")
        urlRequest.httpBody = body.data(using: .utf8)
      }
    case .head, .options:
      throw HttpClientError.unsupportedMethod(httpRequest.method)
    }

    logRequest(httpRequest)
    do {
      let (data, response) = try await session.data(for: urlRequest)
      guard let response = response as? HTTPURLResponse else { throw HttpClientError.notHttp }
      logResponse(code: response.statusCode, data: data, url: httpRequest.url)
      return (response.statusCode, data)
    } catch {
      logger.error("✗ Error for \(httpRequest.url): \(error.localizedDescription)")
      throw error
    }
  }

  /// Sends `httpRequest` and decodes a 2xx body as `T`; any other status throws `HttpException`.
  func requestJson<T: Decodable>(_ httpRequest: HttpRequest, as type: T.Type) async throws -> T {
    let (code, data) = try await request(httpRequest)
    guard (200 ..< 300).contains(code) else {
      throw HttpException(code: code, responseBody: String(decoding: data, as: UTF8.self))
    }
    do {
      return try JSONDecoder().decode(type, from: data)
    } catch let DecodingError.keyNotFound(key, context) {
      throw HttpClientError.undecodable("Missing required key '\(key.stringValue)' at path: \(Self.path(context))")
    } catch let DecodingError.typeMismatch(type, context) {
      throw HttpClientError.undecodable("Type mismatch for \(type) at path: \(Self.path(context))")
    } catch let DecodingError.valueNotFound(type, context) {
      throw HttpClientError.undecodable("Value not found for \(type) at path: \(Self.path(context))")
    }
  }

  private static func path(_ context: DecodingError.Context) -> String {
    context.codingPath.map(\.stringValue).joined(separator: ".")
  }
}

// MARK: - Logging

extension HttpClient {
  private func logRequest(_ request: HttpRequest) {
    logger.debug("→ \(request.method.stringValue) \(request.url)")
    for (key, value) in request.headers ?? [:] {
      logger.debug("  \(key): \(value)")
    }
    if let body = request.body {
      logger.debug("  Body: \(body.prefix(500))\(body.count > 500 ? "..." : "")")
    }
  }

  private func logResponse(code: Int, data: Data, url: String) {
    logger.debug("← \((200 ..< 300).contains(code) ? "✓" : "✗") \(code) \(url)")
    if !data.isEmpty {
      logger.debug("  Body: \(String(decoding: data.prefix(500), as: UTF8.self))\(data.count > 500 ? "..." : "")")
    }
  }
}
