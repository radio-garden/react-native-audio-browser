import Foundation

/// A thread-safe primitive that resolves to a value once, allowing multiple waiters.
/// Once resolved, all current and future `wait()` calls return immediately with the value.
final class OnceValue<T: Sendable>: @unchecked Sendable {
    private var continuations: [CheckedContinuation<T, Never>] = []
    private var value: T?
    private let lock = NSLock()
    private let provider: (() -> T?)?

    /// Creates a OnceValue with a provider closure that returns the value when ready.
    /// Call `check()` to attempt resolution using the provider.
    init(provider: @escaping () -> T?) {
        self.provider = provider
    }

    /// Creates a OnceValue without a provider. Use `resolve(_:)` to set the value manually.
    init() {
        self.provider = nil
    }

    /// Checks if the value is ready using the provider closure.
    /// If the provider returns a value, resolves and resumes all waiters.
    func check() {
        guard let provider, let value = provider() else { return }
        resolve(value)
    }

    /// Resolves the value, resuming all waiting continuations.
    /// Subsequent calls are ignored if already resolved.
    func resolve(_ value: T) {
        lock.lock()
        guard self.value == nil else {
            lock.unlock()
            return
        }
        self.value = value
        let pending = continuations
        continuations.removeAll()
        lock.unlock()

        for continuation in pending {
            continuation.resume(returning: value)
        }
    }

    /// Waits for the value to be resolved. Returns immediately if already resolved.
    func wait() async -> T {
        // All lock operations happen inside the synchronous continuation closure
        // to avoid Swift concurrency warnings about locks in async contexts
        await withCheckedContinuation { continuation in
            lock.lock()
            if let value {
                lock.unlock()
                continuation.resume(returning: value)
            } else {
                continuations.append(continuation)
                lock.unlock()
            }
        }
    }

    /// Resets the value, allowing it to be resolved again.
    /// Any pending waiters will continue waiting for the next resolve.
    func reset() {
        lock.lock()
        value = nil
        lock.unlock()
    }
}
