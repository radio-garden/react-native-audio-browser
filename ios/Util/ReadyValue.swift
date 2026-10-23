import Foundation

/// A value that exists once `provider` returns one. `wait` returns it, parking until `check()`
/// finds it; nothing is cached, so the provider's current answer always wins.
@MainActor
final class ReadyValue<T: Sendable> {
  private let provider: () -> T?
  private var waiters: [UUID: CheckedContinuation<T?, Never>] = [:]

  init(provider: @escaping () -> T?) {
    self.provider = provider
  }

  var waiterCount: Int {
    waiters.count
  }

  /// Resumes every parked waiter if the provider now has a value.
  func check() {
    guard !waiters.isEmpty, let value = provider() else { return }
    let pending = waiters
    waiters = [:]
    for waiter in pending.values {
      waiter.resume(returning: value)
    }
  }

  /// The provider's value, waiting for it when there is none yet. Nil when the calling task is
  /// cancelled or `timeout` elapses first.
  func wait(timeout: Duration? = nil) async -> T? {
    if let value = provider() { return value }
    let id = UUID()
    let timer = timeout.map { timeout in
      Task { [weak self] in
        try? await Task.sleep(for: timeout)
        self?.resume(id, with: nil)
      }
    }
    defer { timer?.cancel() }
    return await withTaskCancellationHandler {
      await withCheckedContinuation { continuation in
        if Task.isCancelled {
          continuation.resume(returning: nil)
        } else {
          waiters[id] = continuation
        }
      }
    } onCancel: {
      Task { @MainActor [weak self] in self?.resume(id, with: nil) }
    }
  }

  private func resume(_ id: UUID, with value: T?) {
    waiters.removeValue(forKey: id)?.resume(returning: value)
  }
}
