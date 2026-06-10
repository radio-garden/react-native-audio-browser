import Foundation

/// Pure, Nitro-free composition logic for the media `resolve` layer.
///
/// The two pieces that need testing in isolation from the Nitro bridge are:
///  1. the variant unwrap — `resolve(track)` returns a `RequestConfig` either
///     synchronously (`.first`) or via a `Promise` (`.second`); both arms must
///     yield the same `RequestConfig`; and
///  2. the merge — the resolved config is layered over the base with
///     override-wins per field (and per-key dict merge for headers/query).
///
/// Production (`BrowserManager+URLResolution.swift`) builds the Nitro
/// `RequestConfig` values and calls into `MediaResolveComposer.merge` /
/// `.unwrap`; the SPM test target exercises the same functions against the
/// stub `RequestConfigLike` value type. Keeping the logic here (rather than in
/// the Nitro-only extension) is what makes it reachable from tests.
enum MediaResolveComposer {
  /// A minimal field set mirroring `RequestConfig`, used so the merge logic can
  /// be expressed once and shared between the Nitro types and the test stub.
  struct RequestConfigLike: Equatable {
    var method: String?
    var path: String?
    var baseUrl: String?
    var headers: [String: String]?
    var query: [String: String]?
    var body: String?
    var contentType: String?
    var userAgent: String?

    init(
      method: String? = nil,
      path: String? = nil,
      baseUrl: String? = nil,
      headers: [String: String]? = nil,
      query: [String: String]? = nil,
      body: String? = nil,
      contentType: String? = nil,
      userAgent: String? = nil,
    ) {
      self.method = method
      self.path = path
      self.baseUrl = baseUrl
      self.headers = headers
      self.query = query
      self.body = body
      self.contentType = contentType
      self.userAgent = userAgent
    }
  }

  /// The unwrapped result of a `resolve(track)` variant.
  enum ResolveVariant<Config> {
    /// `.first` — a synchronous config.
    case sync(Config)
    /// `.second` — a config delivered via an async closure (a Promise in prod).
    case async(() async throws -> Config)
  }

  /// Unwraps a sync/async config into a concrete config, awaiting the async arm
  /// if needed. (Production no longer uses a Nitro variant — `resolve`/`resolveSync`
  /// are separate fields — but this stays as a pure, testable composition helper.)
  static func unwrap<Config>(_ variant: ResolveVariant<Config>) async throws -> Config {
    switch variant {
    case let .sync(config):
      config
    case let .async(makeConfig):
      try await makeConfig()
    }
  }

  /// Run-both resolve composition: the async-resolved config first, then the
  /// sync-resolved merged over it (sync winning) via `combine`. `nil` when neither
  /// produced a config. Generic so it is shared verbatim by production (Nitro
  /// `RequestConfig` + `mergeRequestConfig`) and tests (`RequestConfigLike` +
  /// `merge`) — one implementation, exercised on both sides.
  static func composeResolved<C>(
    async asyncResolved: C?,
    sync syncResolved: C?,
    combine: (C, C) -> C,
  ) -> C? {
    switch (asyncResolved, syncResolved) {
    case (nil, nil): nil
    case let (value?, nil): value
    case let (nil, value?): value
    case let (a?, s?): combine(a, s)
    }
  }

  /// Override-wins per-field merge. Headers and query merge per-key (override
  /// key wins). Matches `BrowserManager.mergeRequestConfig`.
  static func merge(base: RequestConfigLike, override: RequestConfigLike) -> RequestConfigLike {
    RequestConfigLike(
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

  /// Per-key dict merge with override-wins; nil-safe (returns the non-nil side,
  /// or nil when both are nil). Matches `BrowserManager.mergeDicts`.
  static func mergeDicts(_ base: [String: String]?, _ override: [String: String]?) -> [String: String]? {
    switch (base, override) {
    case (nil, nil): nil
    case let (base?, nil): base
    case let (nil, override?): override
    case let (base?, override?): base.merging(override) { _, new in new }
    }
  }
}
