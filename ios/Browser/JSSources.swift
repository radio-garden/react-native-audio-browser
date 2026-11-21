#if canImport(NitroModules)
  import NitroModules
#endif

/// Calls into the app's JS browse and search sources and layer resolvers. Each fails with
/// `BrowserError.callbackError` for what the source throws or rejects with
/// (`BrowserError.fromCallback`).
enum JSSources {
  /// A browse callback's page; its `{ error }` result throws `.callbackError` with that message.
  nonisolated(nonsending) static func browse(
    _ callback: (BrowserSourceCallbackParam) -> Promise<Variant_ResolvedTrack_BrowseError_Promise_BrowseResult_>,
    _ param: BrowserSourceCallbackParam,
  ) async throws -> ResolvedTrack {
    try await BrowserError.fromCallback {
      // Nitro flattens `(ResolvedTrack | BrowseError) | Promise<BrowseResult>` into three arms:
      // a sync track, a sync error, or a promise of the same two.
      switch try await callback(param).awaitCancellable() {
      case let .first(resolvedTrack):
        return resolvedTrack
      case let .second(browseError):
        throw BrowserError.callbackError(browseError.error)
      case let .third(promise):
        switch try await promise.awaitJSPromise() {
        case let .first(resolvedTrack):
          return resolvedTrack
        case let .second(browseError):
          throw BrowserError.callbackError(browseError.error)
        }
      }
    }
  }

  /// A source that returns a JS promise behind Nitro's: a search callback, or a layer resolver,
  /// whose sync-or-async thunk the TS side wraps in a promise.
  nonisolated(nonsending) static func nested<T>(_ call: () -> Promise<Promise<T>>) async throws -> T {
    try await BrowserError.fromCallback { try await call().awaitNested() }
  }
}
