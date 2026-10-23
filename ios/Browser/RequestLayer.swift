import Foundation

/// A request config's fields: the Nitro `RequestConfig` in the app, a plain struct in tests.
protocol RequestFields {
  associatedtype Method
  var method: Method? { get }
  var path: String? { get }
  var baseUrl: String? { get }
  var headers: [String: String]? { get }
  var query: [String: String]? { get }
  var body: String? { get }
  var contentType: String? { get }
  var userAgent: String? { get }

  init(
    method: Method?,
    path: String?,
    baseUrl: String?,
    headers: [String: String]?,
    query: [String: String]?,
    body: String?,
    contentType: String?,
    userAgent: String?,
  )
}

/// How Request-Config Layers compose, least specific first, each over the one before. The one
/// implementation of these rules; Android and the web stub follow the same ones.
enum RequestLayer {
  /// `override` over `base`, field by field; headers and query merge by key.
  static func merge<C: RequestFields>(_ base: C, _ override: C) -> C {
    C(
      method: override.method ?? base.method,
      path: override.path ?? base.path,
      baseUrl: override.baseUrl ?? base.baseUrl,
      headers: mergeDicts(base.headers, override.headers),
      query: mergeDicts(base.query, override.query),
      body: override.body ?? base.body,
      contentType: override.contentType ?? base.contentType,
      userAgent: override.userAgent ?? base.userAgent,
    )
  }

  /// A layer's static fields over `base`, keeping the base's path: only a transform may change
  /// the path.
  static func applyStatic<C: RequestFields>(_ layer: C, to base: C) -> C {
    let merged = merge(base, layer)
    return C(
      method: merged.method, path: base.path, baseUrl: merged.baseUrl, headers: merged.headers,
      query: merged.query, body: merged.body, contentType: merged.contentType, userAgent: merged.userAgent,
    )
  }

  /// Applies one layer: its transforms, when it has any, win completely, each handed the result
  /// of the one before; otherwise its static fields apply over `base`.
  nonisolated(nonsending) static func apply<C: RequestFields>(
    _ layer: C,
    transforms: [nonisolated(nonsending)(C) async throws -> C],
    to base: C,
  ) async throws -> C {
    guard !transforms.isEmpty else { return applyStatic(layer, to: base) }
    var result = base
    for transform in transforms {
      result = try await transform(result)
    }
    return result
  }

  /// The configs a `resolve` (async) and `resolveSync` produced, the sync one winning; nil when
  /// neither produced one.
  static func mergeResolved<C: RequestFields>(async asyncResolved: C?, sync syncResolved: C?) -> C? {
    guard let asyncResolved else { return syncResolved }
    guard let syncResolved else { return asyncResolved }
    return merge(asyncResolved, syncResolved)
  }

  /// The URL a config addresses: base URL, then path (or `defaultPath`), then query.
  static func buildUrl(_ config: some RequestFields, defaultPath: String = "") -> String {
    let url = BrowserPathHelper.buildUrl(baseUrl: config.baseUrl, path: config.path ?? defaultPath)
    guard let query = config.query, !query.isEmpty else { return url }
    return BrowserPathHelper.appendQuery(query, to: url)
  }

  /// `override`'s keys over `base`'s; nil only when both are nil.
  static func mergeDicts(_ base: [String: String]?, _ override: [String: String]?) -> [String: String]? {
    guard let base else { return override }
    guard let override else { return base }
    return base.merging(override) { _, new in new }
  }
}
