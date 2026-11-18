import Foundation

public struct EmitterToken: Sendable {
  fileprivate let id: Int
}

/// Delivers each event to every listener, synchronously on the emitting thread. Any method may be
/// called from any thread, including from inside a listener.
public final class Emitter<T>: @unchecked Sendable {
  private let lock = NSLock()
  private var nextId = 0
  private var snapshot: [(id: Int, handler: (T) -> Void)] = []

  public init() {}

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

  public func removeListener(_ token: EmitterToken) {
    lock.lock()
    defer { lock.unlock() }

    var next = snapshot
    next.removeAll { $0.id == token.id }
    snapshot = next
  }

  public func emit(_ event: T) {
    // Listeners run outside the lock, so one may add or remove listeners; a listener removed
    // during an emit still receives that event.
    lock.lock()
    let current = snapshot
    lock.unlock()

    for entry in current {
      entry.handler(event)
    }
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

extension Emitter {
  /// Like `subscribe`, but `handler` runs later on the main actor, in a Task, whichever thread
  /// emitted. Off main it may run before the emit returns.
  func subscribeOnMain(_ handler: @escaping @MainActor (T) -> Void) -> Subscription {
    subscribe { value in
      let event = MainActorHop(value: value)
      Task { @MainActor in handler(event.value) }
    }
  }
}

/// Carries a non-Sendable emitted value to the main actor, where it is only read.
private struct MainActorHop<T>: @unchecked Sendable {
  let value: T
}
