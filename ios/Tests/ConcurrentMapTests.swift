@testable import AudioBrowserTestable
import Testing

@Suite("concurrentMap")
struct ConcurrentMapTests {
  @Test @MainActor
  func keepsElementOrder_whenLaterElementsFinishFirst() async {
    var finished: [Int] = []
    let results = await [30, 20, 10].concurrentMap { delayMs in
      try? await Task.sleep(for: .milliseconds(delayMs))
      finished.append(delayMs)
      return delayMs * 2
    }
    #expect(finished == [10, 20, 30])
    #expect(results == [60, 40, 20])
  }

  @Test @MainActor
  func keepsNilResults_inPlace() async {
    let results = await ["a", "", "c"].concurrentMap { $0.isEmpty ? nil : $0 }
    #expect(results == ["a", nil, "c"])
  }

  @Test @MainActor
  func empty_returnsEmpty() async {
    let results = await [Int]().concurrentMap { $0 }
    #expect(results.isEmpty)
  }

  @Test @MainActor
  func cancellingTheCaller_cancelsEveryTransform() async {
    let task = Task { @MainActor in
      await [1, 2].concurrentMap { _ in
        do {
          try await Task.sleep(for: .seconds(10))
          return false
        } catch {
          return Task.isCancelled
        }
      }
    }
    task.cancel()
    #expect(await task.value == [true, true])
  }
}
