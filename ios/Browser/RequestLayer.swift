import Foundation
#if canImport(NitroModules)
  import NitroModules
#endif

/// A request config's fields, as every config kind (request, browse, media, artwork) has them.
protocol RequestFieldValues<Method> {
  associatedtype Method
  var method: Method? { get }
  var path: String? { get }
  var baseUrl: String? { get }
  var headers: [String: String]? { get }
  var query: [String: String]? { get }
  var body: String? { get }
  var contentType: String? { get }
  var userAgent: String? { get }
}

/// A request config the layers build: the Nitro `RequestConfig` in the app, a plain struct in tests.
protocol RequestFields: RequestFieldValues {
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

extension RequestFields {
  /// The config a layer stack starts from: only `path` set.
  static func base(path: String?) -> Self {
    Self(method: nil, path: path, baseUrl: nil, headers: nil, query: nil, body: nil, contentType: nil, userAgent: nil)
  }

  /// `fields`' values, copied.
  init(fieldsOf fields: some RequestFieldValues<Method>) {
    self.init(
      method: fields.method, path: fields.path, baseUrl: fields.baseUrl, headers: fields.headers,
      query: fields.query, body: fields.body, contentType: fields.contentType, userAgent: fields.userAgent,
    )
  }
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

  // MARK: - Artwork

  /// An artwork request, in order: `artwork`'s static fields over `base`, path included; the
  /// per-track config `resolve` produces; `sizeQuery`; the transforms, each handed the result of
  /// the one before; then the track's `id` for `{id}`. Nil when `resolve` produces nothing for a
  /// track without artwork, or when no path remains.
  nonisolated(nonsending) static func artwork<C: RequestFields>(
    _ artwork: C,
    over base: C,
    resolve: (nonisolated(nonsending)() async throws -> C?)?,
    trackHasArtwork: Bool,
    sizeQuery: [String: String],
    transforms: [nonisolated(nonsending)(C) async throws -> C],
    id: String?,
  ) async throws -> C? {
    var request = merge(base, artwork)
    if let resolve {
      if let resolved = try await resolve() {
        request = merge(request, resolved)
      } else if !trackHasArtwork {
        return nil
      }
    }
    if !sizeQuery.isEmpty {
      request = merge(request, C(
        method: nil, path: nil, baseUrl: nil, headers: nil,
        query: sizeQuery, body: nil, contentType: nil, userAgent: nil,
      ))
    }
    for transform in transforms {
      request = try await transform(request)
    }
    request = C(
      method: request.method,
      path: TrackIdTemplate.substitute(request.path, id: id),
      baseUrl: request.baseUrl,
      headers: TrackIdTemplate.substitute(request.headers, id: id),
      query: TrackIdTemplate.substitute(request.query, id: id),
      body: request.body,
      contentType: request.contentType,
      userAgent: request.userAgent,
    )
    return request.path == nil ? nil : request
  }

  /// The image-size query `params` names for `context`'s width and height, in whole pixels.
  static func sizeQuery(_ params: ImageQueryParams?, _ context: ImageContext?) -> [String: String] {
    guard let params, let context else { return [:] }
    var query: [String: String] = [:]
    if let key = params.width, let width = context.width { query[key] = String(Int(width)) }
    if let key = params.height, let height = context.height { query[key] = String(Int(height)) }
    return query
  }

  /// Whether `request` still carries an `{id}` token: the track had no id to fill it.
  static func hasUnfilledIdToken(_ request: some RequestFieldValues) -> Bool {
    TrackIdTemplate.containsToken(request.path)
      || TrackIdTemplate.containsToken(request.query)
      || TrackIdTemplate.containsToken(request.headers)
  }

  /// The headers an image load sends: `request.headers`, plus `userAgent` as `User-Agent` unless
  /// a header sets it. Nil when there are none.
  static func imageHeaders(_ request: some RequestFieldValues) -> [String: String]? {
    var headers = request.headers ?? [:]
    if let userAgent = request.userAgent, headers["User-Agent"] == nil {
      headers["User-Agent"] = userAgent
    }
    return headers.isEmpty ? nil : headers
  }
}
