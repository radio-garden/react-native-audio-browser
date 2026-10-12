import NitroModules

/// The Browse Gate: an app-imposed block on browsing and searching from external surfaces,
/// decided per request. It never blocks the Player, the Queue or Now Playing.
@MainActor
final class BrowseGate {
  enum Mode {
    case off
    /// Every request is gated, with this chrome or the built-in one.
    case fixed(Gate?)
    /// JS decides each request; `default` is the chrome when its decision carries none.
    case resolved(default: Gate?)
  }

  /// Served when a request is gated with no chrome from JS or `setGate`.
  static let builtInChrome = Gate(title: "Unavailable", message: nil)

  private(set) var mode: Mode = .off

  /// Fires `isActive` on every set and clear; chrome is per request, so surfaces re-render.
  let changed = Emitter<Bool>()

  /// Asks JS for a decision; set by the bridge, whose JS resolver it calls.
  var resolve: @MainActor (NativeGateRequest) async throws -> GateDecision = { _ in throw CancellationError() }

  nonisolated init() {}

  var isActive: Bool {
    if case .off = mode { false } else { true }
  }

  func set(chrome: Gate?, hasResolver: Bool) {
    mode = hasResolver ? .resolved(default: chrome) : .fixed(chrome)
    changed.emit(true)
  }

  func clear() {
    guard isActive else { return }
    mode = .off
    changed.emit(false)
  }

  /// The chrome to show if `request` is gated; nil when it is allowed. A resolver that fails
  /// cannot prove the request allowed, so it is gated: a gate exists to withhold content, and
  /// serving on an error would leak it.
  func decide(_ request: NativeGateRequest) async -> Gate? {
    switch mode {
    case .off:
      return nil
    case let .fixed(chrome):
      return chrome ?? Self.builtInChrome
    case let .resolved(defaultChrome):
      guard let decision = try? await resolve(request) else { return defaultChrome ?? Self.builtInChrome }
      guard decision.gated else { return nil }
      return decision.gate ?? defaultChrome ?? Self.builtInChrome
    }
  }
}
