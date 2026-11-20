import Foundation

/// Runs `onExit` on the main actor once `thread` exits. Releasing the observer stops it, even
/// when the thread has exited and `onExit` is still on its way to the main actor.
final class ThreadExitObserver {
  @MainActor private final class Handler {
    let onExit: @MainActor () -> Void

    init(_ onExit: @escaping @MainActor () -> Void) {
      self.onExit = onExit
    }
  }

  private let handler: Handler
  private let token: NSObjectProtocol

  @MainActor
  init(_ thread: Thread, onExit: @escaping @MainActor () -> Void) {
    let handler = Handler(onExit)
    self.handler = handler
    token = NotificationCenter.default.addObserver(
      forName: .NSThreadWillExit, object: thread, queue: nil,
    ) { [weak handler] _ in
      DispatchQueue.main.async { handler?.onExit() }
    }
  }

  deinit {
    NotificationCenter.default.removeObserver(token)
  }
}
