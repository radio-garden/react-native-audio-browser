import Foundation

/// How long a JS callback with a fallback may stay silent before its caller falls back.
let callbackFallbackTimeout: Duration = .seconds(10)

/// Thrown when a JS callback stays silent past its timeout. Not a `CancellationError`: the
/// caller is still running and falls back.
struct CallbackTimeoutError: Error {}

/// Hands the subscriber the closure to call with the callback's result. Calls after the first,
/// and any after the wait has ended, are ignored.
typealias CallbackSubscription<T> = (_ deliver: @escaping @Sendable (Result<T, Error>) -> Void) -> Void

/// The result `subscribe` delivers. Unlike Nitro's `Promise.await`, a cancelled caller throws
/// `CancellationError` at once, and with a `timeout`, a silent callback throws
/// `CallbackTimeoutError` once it elapses.
nonisolated(nonsending) func awaitCallback<T>(timeout: Duration? = nil, _ subscribe: CallbackSubscription<T>) async throws -> T {
  let once = ResumeOnce<T>()
  let timer = timeout.map { timeout in
    Task {
      try await Task.sleep(for: timeout)
      once.resume(.failure(CallbackTimeoutError()))
    }
  }
  defer { timer?.cancel() }
  return try await withTaskCancellationHandler {
    try await withCheckedThrowingContinuation { continuation in
      once.start(continuation)
      subscribe { once.resume($0) }
    }
  } onCancel: {
    once.resume(.failure(CancellationError()))
  }
}

/// Resumes its continuation with the first result only, whichever thread sends it and whether it
/// comes before or after the continuation exists. A result is handed over once and never read
/// again, hence `nonisolated(unsafe)`.
private final class ResumeOnce<T>: @unchecked Sendable {
  private enum State {
    case waiting
    case early(Result<T, Error>)
    case started(CheckedContinuation<T, Error>)
    case done
  }

  private let lock = NSLock()
  private var state = State.waiting

  func start(_ continuation: CheckedContinuation<T, Error>) {
    let early: Result<T, Error>? = lock.withLock {
      guard case let .early(result) = state else {
        state = .started(continuation)
        return nil
      }
      state = .done
      return result
    }
    guard let early else { return }
    nonisolated(unsafe) let result = early
    continuation.resume(with: result)
  }

  func resume(_ result: Result<T, Error>) {
    let continuation: CheckedContinuation<T, Error>? = lock.withLock {
      switch state {
      case .waiting:
        state = .early(result)
        return nil
      case let .started(continuation):
        state = .done
        return continuation
      case .early, .done:
        return nil
      }
    }
    nonisolated(unsafe) let result = result
    continuation?.resume(with: result)
  }
}

/// A JS callback's failure, its message as `localizedDescription`. Nitro's `RuntimeError`, which
/// hands Swift the failure, is a `LocalizedError` only from 0.37.2: before, its message is only its
/// `description`.
struct JSCallbackError: LocalizedError {
  let message: String
  var errorDescription: String? { message }

  init(_ rejection: Error) {
    message = String(describing: rejection)
  }

  /// For the rejection of a promise a JS callback returned, which reaches Swift as `Name: message`.
  init(jsPromiseRejection rejection: Error) {
    message = withoutErrorName(String(describing: rejection))
  }
}

private func withoutErrorName(_ message: String) -> String {
  guard let name = message.prefixMatch(of: #/(?:[A-Za-z_$][\w$]*)?Error: /#) else { return message }
  return String(message[name.range.upperBound...])
}
