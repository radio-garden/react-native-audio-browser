extension Array where Element: Sendable {
  /// `transform` over every element at once, with the results in element order. Cancelling the
  /// caller cancels every transform still running.
  func concurrentMap<T: Sendable>(_ transform: @escaping @MainActor (Element) async -> T) async -> [T] {
    await withTaskGroup(of: (Int, T).self) { group in
      for (index, element) in enumerated() {
        group.addTask {
          let result = await transform(element)
          return (index, result)
        }
      }
      var results: [(Int, T)] = []
      for await result in group {
        results.append(result)
      }
      return results.sorted { $0.0 < $1.0 }.map(\.1)
    }
  }
}
