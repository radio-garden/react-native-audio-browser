import NitroModules

/// Awaits for JS callbacks in place of Nitro's `await()`, which ignores cancellation: see
/// `awaitCallback`. A rejection throws Nitro's `RuntimeError`; `awaitJSPromise`, and the inner promise
/// of the nested forms, throw a `JSCallbackError` instead. The nested forms are for a callback returning
/// a promise, which crosses the bridge as `Promise<Promise<U>>`.
extension Promise {
  func awaitCancellable() async throws -> T {
    try await awaitCallback(subscribe)
  }

  /// Throws `CallbackTimeoutError` after `callbackFallbackTimeout`; only for a caller with a
  /// fallback.
  func awaitOrTimeout() async throws -> T {
    try await awaitCallback(timeout: callbackFallbackTimeout, subscribe)
  }

  /// `awaitCancellable` for a promise a JS callback returned.
  func awaitJSPromise() async throws -> T {
    try await awaitCallback(subscribeJS)
  }

  func awaitNested<U>() async throws -> U where T == Promise<U> {
    try await awaitCallback(subscribeNested)
  }

  /// The timeout covers both promises.
  func awaitNestedOrTimeout<U>() async throws -> U where T == Promise<U> {
    try await awaitCallback(timeout: callbackFallbackTimeout, subscribeNested)
  }

  private func subscribe(_ deliver: @escaping @Sendable (Result<T, Error>) -> Void) {
    then { deliver(.success($0)) }.catch { deliver(.failure($0)) }
  }

  private func subscribeJS(_ deliver: @escaping @Sendable (Result<T, Error>) -> Void) {
    then { deliver(.success($0)) }.catch { deliver(.failure(JSCallbackError(jsPromiseRejection: $0))) }
  }

  private func subscribeNested<U>(_ deliver: @escaping @Sendable (Result<U, Error>) -> Void) where T == Promise<U> {
    then { $0.subscribeJS(deliver) }.catch { deliver(.failure($0)) }
  }
}
