import Testing

@testable import AudioBrowserTestable

@MainActor
@Suite("RoutePickerSourceLifetime")
struct RoutePickerSourceLifetimeTests {
  final class Source {}

  @MainActor
  final class Removals {
    var removed: [ObjectIdentifier] = []
    lazy var lifetime = RoutePickerSourceLifetime<Source> { [unowned self] in
      removed.append(ObjectIdentifier($0))
    }

    func wasRemoved(_ source: Source) -> Bool {
      removed.contains(ObjectIdentifier(source))
    }
  }

  @Test("a source stays while its picker shows past the settle time")
  func staysWhilePresenting() {
    let removals = Removals()
    let source = Source()
    removals.lifetime.added(source)
    removals.lifetime.willBeginPresenting(source)
    removals.lifetime.settled(source)
    #expect(!removals.wasRemoved(source))
  }

  @Test("a source goes when its picker ends")
  func goesWhenPickerEnds() {
    let removals = Removals()
    let source = Source()
    removals.lifetime.added(source)
    removals.lifetime.willBeginPresenting(source)
    removals.lifetime.settled(source)
    removals.lifetime.didEndPresenting(source)
    #expect(removals.wasRemoved(source))
  }

  @Test("a source whose picker never began goes when it settles")
  func goesWhenNeverPresented() {
    let removals = Removals()
    let source = Source()
    removals.lifetime.added(source)
    removals.lifetime.settled(source)
    #expect(removals.wasRemoved(source))
  }

  @Test("a picker that never ends keeps its source until the next is added")
  func nextSourceReplacesOneThatNeverEnded() {
    let removals = Removals()
    let first = Source()
    removals.lifetime.added(first)
    removals.lifetime.willBeginPresenting(first)
    removals.lifetime.settled(first)
    #expect(!removals.wasRemoved(first))

    let second = Source()
    removals.lifetime.added(second)
    #expect(removals.wasRemoved(first))
    #expect(!removals.wasRemoved(second))
  }

  @Test("an earlier picker ending leaves the showing one's source")
  func earlierEndLeavesCurrentPresenting() {
    let removals = Removals()
    let first = Source()
    let second = Source()
    removals.lifetime.added(first)
    removals.lifetime.added(second)
    removals.lifetime.willBeginPresenting(second)
    removals.lifetime.didEndPresenting(first)
    removals.lifetime.settled(second)
    #expect(!removals.wasRemoved(second))
  }
}
