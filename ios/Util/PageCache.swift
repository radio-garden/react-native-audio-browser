import Foundation

/// Browse pages by path, each kept for the lifetime its fetch states (nil: until evicted or
/// removed; 0: not kept). `expired` emits a page's path once its lifetime passes.
@MainActor
final class PageCache<Value> {
  private struct Entry {
    let value: Value
    let lifetime: TimeInterval?
    var expiresAt: Date?
    var stamp = 0
  }

  let expired = Emitter<String>()
  private let entries: LRUCache<String, Entry>
  private let now: @MainActor () -> Date
  private let sleep: @MainActor (TimeInterval) async -> Void
  private var stamps = 0
  // Moves on `clear`, so a fetch that outlives one caches nothing; `pathGenerations` does the
  // same for one path on `remove`.
  private var generation = 0
  private var pathGenerations: [String: Int] = [:]

  nonisolated init(
    maxSize: Int,
    now: @escaping @MainActor () -> Date = Date.init,
    sleep: @escaping @MainActor (TimeInterval) async -> Void = { try? await Task.sleep(for: .seconds($0)) },
  ) {
    entries = LRUCache(maxSize: maxSize)
    self.now = now
    self.sleep = sleep
  }

  /// The cached page at `path` while it is fresh and `useCache`, else what `fetch` gives, cached
  /// for the lifetime it states. When `fetch` fails, a cached page is kept and expires again a
  /// lifetime later; with `useCache` it is returned, stale, in place of the error.
  func value(
    for path: String,
    useCache: Bool,
    fetch: () async throws -> (value: Value, lifetime: TimeInterval?),
  ) async throws -> Value {
    let cached = entries.get(path)
    if useCache, let cached, cached.expiresAt.map({ $0 > now() }) ?? true {
      return cached.value
    }
    let generation = generation(of: path)
    do {
      let (value, lifetime) = try await fetch()
      if generation == self.generation(of: path) { store(path, Entry(value: value, lifetime: lifetime)) }
      return value
    } catch {
      guard let cached, !(error is CancellationError || Task.isCancelled) else { throw error }
      if generation == self.generation(of: path) { store(path, cached) }
      if useCache { return cached.value }
      throw error
    }
  }

  func remove(_ path: String) {
    pathGenerations[path, default: 0] += 1
    entries.remove(path)
  }

  func clear() {
    generation &+= 1
    pathGenerations.removeAll()
    entries.clear()
  }

  private func generation(of path: String) -> (Int, Int?) {
    (generation, pathGenerations[path])
  }

  private func store(_ path: String, _ entry: Entry) {
    guard entry.lifetime != 0 else { return entries.remove(path) }
    var entry = entry
    entry.expiresAt = entry.lifetime.map { now().addingTimeInterval($0) }
    stamps &+= 1
    entry.stamp = stamps
    entries.set(path, value: entry)
    if let expiresAt = entry.expiresAt { armTimer(path, stamp: entry.stamp, at: expiresAt) }
  }

  private func armTimer(_ path: String, stamp: Int, at expiresAt: Date) {
    let (now, sleep) = (now, sleep)
    Task { [weak self] in
      while now() < expiresAt {
        await sleep(expiresAt.timeIntervalSince(now()))
      }
      guard let self, entries.get(path)?.stamp == stamp else { return }
      expired.emit(path)
    }
  }
}
