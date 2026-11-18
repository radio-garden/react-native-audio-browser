import NitroModules

extension RequestConfig {
  /// A copy with only the given fields changed: an omitted argument keeps the field, `.some(nil)`
  /// clears it.
  func copying(
    method: HttpMethod?? = nil,
    path: String?? = nil,
    baseUrl: String?? = nil,
    headers: [String: String]?? = nil,
    query: [String: String]?? = nil,
    body: String?? = nil,
    contentType: String?? = nil,
    userAgent: String?? = nil,
  ) -> RequestConfig {
    RequestConfig(
      method: method ?? self.method,
      path: path ?? self.path,
      baseUrl: baseUrl ?? self.baseUrl,
      headers: headers ?? self.headers,
      query: query ?? self.query,
      body: body ?? self.body,
      contentType: contentType ?? self.contentType,
      userAgent: userAgent ?? self.userAgent,
    )
  }
}
