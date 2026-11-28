import Foundation

/// Browse pages by path, each kept for the lifetime its fetch states, or with none until evicted
/// or removed. `expired` emits a page's path when its lifetime passes.
@MainActor
final class PageCache<Value> {
  /// Runs `work` after `delay` seconds unless the returned closure cancels it first.
  typealias Schedule = @MainActor (_ delay: TimeInterval, _ work: @escaping @MainActor () -> Void) -> () -> Void

  /// Failed refetches of an expired page pushed again a lifetime apart before it waits for a visit.
  static var maxRetries: Int {
    3
  }

  private struct Entry {
    let value: Value
    let lifetime: TimeInterval?
    /// On `now`'s clock.
    let expiresAt: TimeInterval?
    let failures: Int
    let cancelTimer: (() -> Void)?
  }

  let expired = Emitter<String>()
  private let entries: LRUCache<String, Entry>
  private let now: @MainActor () -> TimeInterval
  private let schedule: Schedule
  /// A path's version moves on every put, remove and clear, so a fetch that outlives one stores
  /// nothing.
  private var versions: [String: Int] = [:]
  private var clearedVersion = 0
  private var lastVersion = 0

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

  /// The cached page at `path` while it is fresh, else what `fetch` gives, cached for the lifetime
  /// it states and pushed when that passes (a page whose `age` used it up is cached expired, with no
  /// push). When `fetch` fails, an expired page is returned and kept, stale, and pushed again a
  /// lifetime later, up to `maxRetries` times; a page stored meanwhile is returned instead.
  func value(for path: String, fetch: () async throws -> Fetched<Value>) async throws -> Value {
    let cached = entries.get(path)
    if let cached, isFresh(cached) {
      return cached.value
    }
    let version = version(of: path)
    do {
      let fetched = try await fetch()
      if version == self.version(of: path) {
        let expiresAt = fetched.lifetime.map { now() + $0 - fetched.age }
        put(path, fetched.value, lifetime: fetched.lifetime, expiresAt: expiresAt, failures: 0, pushIn: expiresAt.map { $0 - now() })
      }
      return fetched.value
    } catch {
      if error is CancellationError || Task.isCancelled { throw error }
      if let cached, version == self.version(of: path) {
        put(path, cached.value, lifetime: cached.lifetime, expiresAt: cached.expiresAt, failures: cached.failures + 1, pushIn: cached.lifetime)
        return cached.value
      }
      // Nil when the page was removed or cleared meanwhile: it is gone, stale fallback included.
      if let current = entries.peek(path) { return current.value }
      throw error
    }
  }

  /// Whether `path` holds a page whose lifetime has passed. Leaves its recency as it is.
  func isExpired(_ path: String) -> Bool {
    entries.peek(path).map { !isFresh($0) } ?? false
  }

  func remove(_ path: String) {
    bump(path)
    entries.remove(path)?.cancelTimer?()
  }

  func clear() {
    lastVersion += 1
    clearedVersion = lastVersion
    versions.removeAll()
    entries.clear().forEach { $0.cancelTimer?() }
  }

  private func isFresh(_ entry: Entry) -> Bool {
    entry.expiresAt.map { $0 > now() } ?? true
  }

  private func version(of path: String) -> Int {
    versions[path] ?? clearedVersion
  }

  private func bump(_ path: String) {
    lastVersion += 1
    versions[path] = lastVersion
  }

  /// Stores the page with a timer that pushes it `pushIn` seconds from now, unless that is not
  /// positive or the page is past `maxRetries` failures.
  private func put(
    _ path: String,
    _ value: Value,
    lifetime: TimeInterval?,
    expiresAt: TimeInterval?,
    failures: Int,
    pushIn: TimeInterval?,
  ) {
    bump(path)
    let cancelTimer = pushIn.flatMap { delay in
      delay > 0 && failures <= Self.maxRetries ? schedule(delay) { [weak self] in self?.expired.emit(path) } : nil
    }
    let entry = Entry(value: value, lifetime: lifetime, expiresAt: expiresAt, failures: failures, cancelTimer: cancelTimer)
    for dropped in entries.set(path, value: entry) {
      dropped.cancelTimer?()
    }
  }
}
