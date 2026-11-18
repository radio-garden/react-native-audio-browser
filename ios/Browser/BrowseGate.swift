#if canImport(NitroModules)
  import NitroModules
#endif

/// Decides the Gate (GLOSSARY.md) per request: the block on browsing and searching from external
/// surfaces. It never blocks the Player, the Queue or Now Playing.
@MainActor
final class BrowseGate {
  enum Mode {
    case off
    /// Every request is gated, with this chrome or the built-in one.
    case fixed(Gate?)
    /// `resolve` decides each request; `default` is the chrome when its decision names none.
    case resolved(default: Gate?)
  }

  static let builtInChrome = Gate(title: "Unavailable", message: nil)

  private(set) var mode: Mode = .off

  /// Emits `isActive` on every `set`, even an unchanged one: chrome is per request, so surfaces
  /// re-render.
  let changed = Emitter<Bool>()

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

  /// The chrome to show when `request` is gated; nil when it may browse. A `resolve` that throws,
  /// the default one included, gates the request: serving on an error would leak what the gate
  /// withholds.
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

  /// The chrome to show when `request` is gated, as `decide(_:)` answers, reported to `report`
  /// unless the caller was cancelled: a cancelled decision still fails closed, but no one met the
  /// gate.
  func decide(_ request: NativeGateRequest, report: (GateEvent) -> Void) async -> Gate? {
    let chrome = await decide(request)
    if chrome != nil, !Task.isCancelled {
      report(GateEvent(reason: request.reason))
    }
    return chrome
  }
}
