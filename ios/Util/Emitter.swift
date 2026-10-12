import Foundation

/// Opaque token returned by `addListener` for targeted removal.
public struct EmitterToken: Sendable {
  fileprivate let id: Int
}

/// Generic event emitter that allows multiple listeners for a single event type
public final class Emitter<T>: @unchecked Sendable {
  private let lock = NSLock()
  private var nextId = 0
  private var snapshot: [(id: Int, handler: (T) -> Void)] = []

  public init() {}

  /// Adds a listener to this emitter
  /// - Parameter listener: The callback to invoke when an event is emitted
  /// - Returns: A token that can be passed to `removeListener` for targeted removal
  @discardableResult
  public func addListener(_ listener: @escaping (T) -> Void) -> EmitterToken {
    lock.lock()
    defer { lock.unlock() }

    let id = nextId
    nextId += 1
    var next = snapshot
    next.append((id: id, handler: listener))
    snapshot = next
    return EmitterToken(id: id)
  }

  /// Removes a specific listener by its token
  /// - Parameter token: The token returned by `addListener`
  public func removeListener(_ token: EmitterToken) {
    lock.lock()
    defer { lock.unlock() }

    var next = snapshot
    next.removeAll { $0.id == token.id }
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

    for entry in current {
      entry.handler(event)
    }
  }

  /// Returns the number of registered listeners
  public var listenerCount: Int {
    lock.lock()
    defer { lock.unlock() }
    return snapshot.count
  }
}

/// A listener registration that ends when cancelled or released.
final class Subscription {
  private var cancelHandler: (() -> Void)?

  init(cancel: @escaping () -> Void) {
    cancelHandler = cancel
  }

  func cancel() {
    cancelHandler?()
    cancelHandler = nil
  }

  deinit {
    cancelHandler?()
  }
}

extension Emitter {
  /// Adds `listener` until the returned subscription is cancelled or released.
  func subscribe(_ listener: @escaping (T) -> Void) -> Subscription {
    let token = addListener(listener)
    return Subscription { [weak self] in self?.removeListener(token) }
  }
}
