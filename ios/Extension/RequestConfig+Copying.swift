import NitroModules

extension RequestConfig {
  /// Returns a copy of this RequestConfig with only the specified fields changed.
  ///
  /// `RequestConfig` is a C++-backed Nitro struct whose fields are get-only, so
  /// "mutation" means rebuilding via the initializer. Uses double-optional
  /// (`T??`) so callers can distinguish omitted (keep), `.some(nil)` (clear),
  /// and `.some(value)` (set).
  func copying(
    method: HttpMethod?? = nil,
    path: String?? = nil,
    baseUrl: String?? = nil,
    headers: [String: String]?? = nil,
    query: [String: String]?? = nil,
    body: String?? = nil,
    contentType: String?? = nil,
    userAgent: String?? = nil
  ) -> RequestConfig {
    RequestConfig(
      method: method ?? self.method,
      path: path ?? self.path,
      baseUrl: baseUrl ?? self.baseUrl,
      headers: headers ?? self.headers,
      query: query ?? self.query,
      body: body ?? self.body,
      contentType: contentType ?? self.contentType,
      userAgent: userAgent ?? self.userAgent
    )
  }
}
