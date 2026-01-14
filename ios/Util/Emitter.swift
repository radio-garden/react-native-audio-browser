import Foundation

/// Generic event emitter that allows multiple listeners for a single event type
public final class Emitter<T>: @unchecked Sendable {
  private let lock = NSLock()
  private var snapshot: [(T) -> Void] = []

  public init() {}

  /// Adds a listener to this emitter
  /// - Parameter listener: The callback to invoke when an event is emitted
  public func addListener(_ listener: @escaping (T) -> Void) {
    lock.lock()
    defer { lock.unlock() }

    var next = snapshot
    next.append(listener)
    snapshot = next
  }

  /// Removes all listeners
  public func removeAllListeners() {
    lock.lock()
    defer { lock.unlock() }
    snapshot = []
  }

  /// Emits an event to all registered listeners
  /// - Parameter event: The event data to emit
  public func emit(_ event: T) {
    lock.lock()
    let current = snapshot // O(1) reference bump; no array copy here
    lock.unlock()

    for listener in current {
      listener(event)
    }
  }

  /// Returns the number of registered listeners
  public var listenerCount: Int {
    lock.lock()
    defer { lock.unlock() }
    return snapshot.count
  }
}
