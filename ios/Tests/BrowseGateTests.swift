import Testing

@testable import AudioBrowserTestable

@MainActor
@Suite("BrowseGate")
struct BrowseGateTests {
  private let request = NativeGateRequest(reason: .search, path: nil, search: nil)

  private func resolvedGate(_ resolve: @escaping @MainActor (NativeGateRequest) async throws -> GateDecision) -> BrowseGate {
    let gate = BrowseGate()
    gate.set(chrome: nil, hasResolver: true)
    gate.resolve = resolve
    return gate
  }

  @Test func reportsAGatedRequest() async {
    let gate = resolvedGate { _ in GateDecision(gated: true, gate: nil) }
    var reports: [GateReason] = []
    let chrome = await gate.decide(request) { reports.append($0.reason) }
    #expect(chrome?.title == BrowseGate.builtInChrome.title)
    #expect(reports == [.search])
  }

  @Test func reportsNothingForAnAllowedRequest() async {
    let gate = resolvedGate { _ in GateDecision(gated: false, gate: nil) }
    var reports = 0
    let chrome = await gate.decide(request) { _ in reports += 1 }
    #expect(chrome == nil)
    #expect(reports == 0)
  }

  @Test func aCancelledDecisionFailsClosedWithoutAReport() async {
    var resolving = false
    let gate = resolvedGate { _ in
      resolving = true
      return try await awaitCallback { (_: @escaping @Sendable (Result<GateDecision, Error>) -> Void) in }
    }
    var reports = 0
    let decision = Task { await gate.decide(request) { _ in reports += 1 } }
    for _ in 0 ..< 1000 where !resolving {
      await Task.yield()
    }
    #expect(resolving)
    decision.cancel()
    let chrome = await decision.value
    #expect(chrome?.title == BrowseGate.builtInChrome.title)
    #expect(reports == 0)
  }
}
