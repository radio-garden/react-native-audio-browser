#if canImport(NitroModules)
  import NitroModules
#endif

/// The Browse Gate: the app's block on browsing and searching from external surfaces,
/// decided per request. It never blocks the Player, the Queue or Now Playing.
@MainActor
final class BrowseGate {
  enum Mode {
    case off
    /// Every request is gated, with this chrome or the built-in one.
    case fixed(Gate?)
    /// JS decides each request; `default` is the chrome when its decision names none.
    case resolved(default: Gate?)
  }

  static let builtInChrome = Gate(title: "Unavailable", message: nil)

  private(set) var mode: Mode = .off

  /// Fires `isActive` on every `set`, even an unchanged one: chrome is per request, so
  /// surfaces re-render.
  let changed = Emitter<Bool>()

  /// Set by the bridge to call the JS resolver. The default throws, which gates every request.
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

  /// The chrome to show if `request` is gated; nil when it is allowed. A resolver that throws
  /// gates the request: serving on an error would leak what the gate withholds.
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

  /// The chrome to show if `request` is gated, as `decide(_:)` answers, and the gate passed to
  /// `report`, unless the caller was cancelled: a cancelled decision fails closed, but the user
  /// never met this gate.
  func decide(_ request: NativeGateRequest, report: (GateEvent) -> Void) async -> Gate? {
    let chrome = await decide(request)
    if chrome != nil, !Task.isCancelled {
      report(GateEvent(reason: request.reason))
    }
    return chrome
  }
}
