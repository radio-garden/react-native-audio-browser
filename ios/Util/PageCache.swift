import Foundation

/// Browse pages by path, each kept for the lifetime its fetch states, or with none until evicted
/// or removed. `expired` emits a page's path once its lifetime passes.
@MainActor
final class PageCache<Value> {
  /// Runs `work` after `delay` seconds unless the returned closure cancels it first.
  typealias Schedule = @MainActor (_ delay: TimeInterval, _ work: @escaping @MainActor () -> Void) -> () -> Void

  /// Failed refreshes of an expired page retried a lifetime apart before it waits for a visit.
  static var maxRetries: Int {
    3
  }

  private struct Entry {
    let value: Value
    let lifetime: TimeInterval?
    /// On `now`'s clock.
    let expiresAt: TimeInterval?
    let stamp: Int
    let failures: Int
    let cancelTimer: (() -> Void)?
  }

  let expired = Emitter<String>()
  /// Moves on `clear`, so a fetch that outlives one caches nothing; `pathGenerations` does the
  /// same for one path on `remove`.
  private(set) var generation = 0
  private var pathGenerations: [String: Int] = [:]
  private let entries: LRUCache<String, Entry>
  private let now: @MainActor () -> TimeInterval
  private let schedule: Schedule
  private var stamps = 0

  nonisolated init(
    maxSize: Int,
    // Seconds on a clock that runs through sleep and ignores changes to the wall clock.
    now: @escaping @MainActor () -> TimeInterval = { TimeInterval(clock_gettime_nsec_np(CLOCK_MONOTONIC)) / 1e9 },
    schedule: @escaping Schedule = { delay, work in
      let task = Task { @MainActor in
        try? await Task.sleep(for: .seconds(delay))
        if !Task.isCancelled { work() }
      }
      return task.cancel
    },
  ) {
    entries = LRUCache(maxSize: maxSize)
    self.now = now
    self.schedule = schedule
  }

  /// The cached page at `path` while it is fresh and `useCache`, else what `fetch` gives, cached
  /// for the lifetime it states. When `fetch` fails for an expired page, the page is kept, expired,
  /// and pushed again a lifetime later, up to `maxRetries` times; with `useCache` it is returned,
  /// stale, in place of the error. A failed refetch of a fresh page changes nothing.
  func value(
    for path: String,
    useCache: Bool,
    fetch: () async throws -> Fetched<Value>,
  ) async throws -> Value {
    let cached = entries.get(path)
    if useCache, let cached, isFresh(cached) {
      return cached.value
    }
    let generation = generation(of: path)
    do {
      let fetched = try await fetch()
      if generation == self.generation(of: path) {
        put(path, Entry(
          value: fetched.value, lifetime: fetched.lifetime, expiresAt: fetched.lifetime.map { now() + $0 },
          stamp: 0, failures: 0, cancelTimer: nil,
        ))
      }
      return fetched.value
    } catch {
      guard let cached, !isFresh(cached), !(error is CancellationError || Task.isCancelled) else { throw error }
      // A newer page stored meanwhile wins over the stale one this fetch replaced.
      let current = entries.peek(path)
      if let current, current.stamp == cached.stamp {
        put(path, Entry(
          value: current.value, lifetime: current.lifetime, expiresAt: current.expiresAt,
          stamp: 0, failures: current.failures + 1, cancelTimer: nil,
        ))
      }
      if useCache { return current?.value ?? cached.value }
      throw error
    }
  }

  func remove(_ path: String) {
    pathGenerations[path, default: 0] += 1
    entries.remove(path)?.cancelTimer?()
  }

  func clear() {
    generation &+= 1
    pathGenerations.removeAll()
    entries.clear().forEach { $0.cancelTimer?() }
  }

  private func isFresh(_ entry: Entry) -> Bool {
    entry.expiresAt.map { $0 > now() } ?? true
  }

  private func generation(of path: String) -> (Int, Int?) {
    (generation, pathGenerations[path])
  }

  /// Stores `entry` under a new stamp, with a timer that pushes it a lifetime from now unless it is
  /// past `maxRetries` failures.
  private func put(_ path: String, _ entry: Entry) {
    stamps &+= 1
    let cancelTimer = entry.lifetime.flatMap { lifetime in
      entry.failures <= Self.maxRetries ? schedule(lifetime) { [weak self] in self?.expired.emit(path) } : nil
    }
    let stored = Entry(
      value: entry.value, lifetime: entry.lifetime, expiresAt: entry.expiresAt,
      stamp: stamps, failures: entry.failures, cancelTimer: cancelTimer,
    )
    for dropped in entries.set(path, value: stored) {
      dropped.cancelTimer?()
    }
  }
}
