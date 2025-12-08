import Foundation
import os.log

/// URLSession-based HTTP client for API requests.
final class HttpClient {
  static let defaultUserAgent = "react-native-audio-browser"
  static let defaultContentType = "application/json"
  private static let timeoutSeconds: TimeInterval = 30

  private let session: URLSession
  private let logger = Logger(subsystem: "com.audiobrowser", category: "HttpClient")

  /// HTTP request configuration (matches Kotlin HttpClient.HttpRequest).
  struct HttpRequest {
    let url: String
    let method: String
    let headers: [String: String]?
    let body: String?
    let contentType: String
    let userAgent: String

    init(
      url: String,
      method: String = "GET",
      headers: [String: String]? = nil,
      body: String? = nil,
      contentType: String = HttpClient.defaultContentType,
      userAgent: String = HttpClient.defaultUserAgent
    ) {
      self.url = url
      self.method = method
      self.headers = headers
      self.body = body
      self.contentType = contentType
      self.userAgent = userAgent
    }
  }

  /// HTTP response data (matches Kotlin HttpClient.HttpResponse).
  struct HttpResponse {
    let code: Int
    let body: String
    let headers: [String: String]

    var isSuccessful: Bool {
      return (200..<300).contains(code)
    }
  }

  /// HTTP exception (matches Kotlin HttpClient.HttpException).
  class HttpException: Error {
    let code: Int
    let responseBody: String

    init(code: Int, responseBody: String) {
      self.code = code
      self.responseBody = responseBody
    }

    var localizedDescription: String {
      return "HTTP \(code): \(responseBody)"
    }
  }

  /// Creates a new HTTP client.
  init(session: URLSession = .shared) {
    self.session = session
  }

  /// Executes an HTTP request (matches Kotlin request() signature).
  func request(_ httpRequest: HttpRequest) async -> Result<HttpResponse, Error> {
    guard let url = URL(string: httpRequest.url) else {
      return .failure(NSError(
        domain: "HttpClient",
        code: -1,
        userInfo: [NSLocalizedDescriptionKey: "Invalid URL: \(httpRequest.url)"]
      ))
    }

    var urlRequest = URLRequest(url: url)
    urlRequest.timeoutInterval = Self.timeoutSeconds

    // Add headers
    httpRequest.headers?.forEach { name, value in
      urlRequest.setValue(value, forHTTPHeaderField: name)
    }
    // Set User-Agent (userAgent parameter takes precedence over headers)
    urlRequest.setValue(httpRequest.userAgent, forHTTPHeaderField: "User-Agent")

    // Add body for non-GET requests
    switch httpRequest.method.uppercased() {
    case "GET":
      urlRequest.httpMethod = "GET"
    case "POST", "PUT", "PATCH":
      urlRequest.httpMethod = httpRequest.method.uppercased()
      urlRequest.setValue(httpRequest.contentType, forHTTPHeaderField: "Content-Type")
      if let body = httpRequest.body {
        urlRequest.httpBody = body.data(using: .utf8)
      }
    case "DELETE":
      urlRequest.httpMethod = "DELETE"
      if let body = httpRequest.body {
        urlRequest.setValue(httpRequest.contentType, forHTTPHeaderField: "Content-Type")
        urlRequest.httpBody = body.data(using: .utf8)
      }
    default:
      return .failure(NSError(
        domain: "HttpClient",
        code: -1,
        userInfo: [NSLocalizedDescriptionKey: "Unsupported HTTP method: \(httpRequest.method)"]
      ))
    }

    // Log request
    logRequest(httpRequest)

    do {
      let (data, response) = try await session.data(for: urlRequest)

      guard let httpResponse = response as? HTTPURLResponse else {
        return .failure(NSError(
          domain: "HttpClient",
          code: -1,
          userInfo: [NSLocalizedDescriptionKey: "Invalid response type"]
        ))
      }

      let responseBody = String(data: data, encoding: .utf8) ?? ""

      // Extract response headers
      var headers: [String: String] = [:]
      for (key, value) in httpResponse.allHeaderFields {
        if let keyString = key as? String, let valueString = value as? String {
          headers[keyString] = valueString
        }
      }

      let httpResp = HttpResponse(
        code: httpResponse.statusCode,
        body: responseBody,
        headers: headers
      )

      // Log response
      logResponse(httpResp, url: httpRequest.url)

      return .success(httpResp)
    } catch {
      logError(error, url: httpRequest.url)
      return .failure(error)
    }
  }

  /// Executes an HTTP request and decodes the JSON response (matches Kotlin requestJson()).
  func requestJson<T: Decodable>(
    _ httpRequest: HttpRequest,
    as type: T.Type
  ) async throws -> T {
    let result = await request(httpRequest)

    switch result {
    case .success(let response):
      if !response.isSuccessful {
        throw HttpException(code: response.code, responseBody: response.body)
      }
      guard let data = response.body.data(using: .utf8) else {
        throw NSError(
          domain: "HttpClient",
          code: -1,
          userInfo: [NSLocalizedDescriptionKey: "Failed to convert response body to data"]
        )
      }
      let decoder = JSONDecoder()
      do {
        return try decoder.decode(type, from: data)
      } catch let DecodingError.keyNotFound(key, context) {
        throw NSError(
          domain: "HttpClient",
          code: -1,
          userInfo: [NSLocalizedDescriptionKey: "Missing required key '\(key.stringValue)' at path: \(context.codingPath.map { $0.stringValue }.joined(separator: "."))"]
        )
      } catch let DecodingError.typeMismatch(type, context) {
        throw NSError(
          domain: "HttpClient",
          code: -1,
          userInfo: [NSLocalizedDescriptionKey: "Type mismatch for \(type) at path: \(context.codingPath.map { $0.stringValue }.joined(separator: "."))"]
        )
      } catch let DecodingError.valueNotFound(type, context) {
        throw NSError(
          domain: "HttpClient",
          code: -1,
          userInfo: [NSLocalizedDescriptionKey: "Value not found for \(type) at path: \(context.codingPath.map { $0.stringValue }.joined(separator: "."))"]
        )
      }
    case .failure(let error):
      throw error
    }
  }
}

// MARK: - Logging

extension HttpClient {
  private func logRequest(_ request: HttpRequest) {
    logger.debug("→ \(request.method) \(request.url)")
    if let headers = request.headers, !headers.isEmpty {
      for (key, value) in headers {
        logger.debug("  \(key): \(value)")
      }
    }
    if let body = request.body {
      let truncated = body.prefix(500)
      logger.debug("  Body: \(truncated)\(body.count > 500 ? "..." : "")")
    }
  }

  private func logResponse(_ response: HttpResponse, url: String) {
    let emoji = response.isSuccessful ? "✓" : "✗"
    logger.debug("← \(emoji) \(response.code) \(url)")
    if !response.body.isEmpty {
      let truncated = response.body.prefix(500)
      logger.debug("  Body: \(truncated)\(response.body.count > 500 ? "..." : "")")
    }
  }

  private func logError(_ error: Error, url: String) {
    logger.error("✗ Error for \(url): \(error.localizedDescription)")
  }
}
