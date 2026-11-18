import Foundation
import Testing

@testable import AudioBrowserTestable

@MainActor
@Suite("ReadyValue")
struct ReadyValueTests {
  final class Source {
    var value: Int?
  }

  @Test func returnsAtOnceWhenTheProviderHasAValue() async {
    let ready = ReadyValue<Int> { 7 }
    #expect(await ready.wait() == 7)
  }

  @Test func resumesEveryWaiterOnCheck() async {
    let source = Source()
    let ready = ReadyValue { source.value }
    let first = Task { await ready.wait() }
    let second = Task { await ready.wait() }
    while ready.waiterCount < 2 {
      await Task.yield()
    }
    ready.check()
    #expect(ready.waiterCount == 2)
    source.value = 3
    ready.check()
    #expect(await first.value == 3)
    #expect(await second.value == 3)
    #expect(ready.waiterCount == 0)
  }

  @Test func followsTheProviderWithoutAReset() async {
    let source = Source()
    source.value = 1
    let ready = ReadyValue { source.value }
    #expect(await ready.wait() == 1)
    source.value = 2
    #expect(await ready.wait() == 2)
  }

  @Test func timesOutWithNilAndDropsTheWaiter() async {
    let ready = ReadyValue<Int> { nil }
    #expect(await ready.wait(timeout: .milliseconds(10)) == nil)
    #expect(ready.waiterCount == 0)
  }

  @Test func aCancelledWaiterGetsNilAndIsDropped() async {
    let ready = ReadyValue<Int> { nil }
    let waiter = Task { await ready.wait() }
    while ready.waiterCount < 1 {
      await Task.yield()
    }
    waiter.cancel()
    #expect(await waiter.value == nil)
    while ready.waiterCount > 0 {
      await Task.yield()
    }
    #expect(ready.waiterCount == 0)
  }

  @Test func aWaiterCancelledBeforeWaitingGetsNil() async {
    let ready = ReadyValue<Int> { nil }
    let waiter = Task {
      withUnsafeCurrentTask { $0?.cancel() }
      return await ready.wait()
    }
    #expect(await waiter.value == nil)
    #expect(ready.waiterCount == 0)
  }
}
