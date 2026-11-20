import Foundation

/// Runs `onExit` on the main actor once `thread` exits. Releasing the observer stops it, even
/// when the thread has exited and `onExit` is still on its way to the main actor.
@MainActor
final class ThreadExitObserver {
  private let onExit: @MainActor () -> Void
  private nonisolated(unsafe) var token: NSObjectProtocol?

  init(_ thread: Thread, onExit: @escaping @MainActor () -> Void) {
    self.onExit = onExit
    token = NotificationCenter.default.addObserver(
      forName: .NSThreadWillExit, object: thread, queue: nil,
    ) { [weak self] _ in
      DispatchQueue.main.async { self?.onExit() }
    }
  }

  deinit {
    token.map(NotificationCenter.default.removeObserver)
  }
}
