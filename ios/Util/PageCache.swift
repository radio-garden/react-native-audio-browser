import Foundation

/// Browse pages by path, each kept for the lifetime its fetch states, or with none until evicted
/// or removed. An expired page is served at once while one refetch per path runs behind it.
/// `expired` emits a page's path when its lifetime passes; `refreshed` when a refetch replaced it.
@MainActor
final class PageCache<Value: Sendable> {
  /// Runs `work` after `delay` seconds unless the returned closure cancels it first.
  typealias Schedule = @MainActor (_ delay: TimeInterval, _ work: @escaping @MainActor () -> Void) -> () -> Void
  typealias Fetch = @MainActor () async throws -> Fetched<Value>

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

  private struct Load {
    let id: Int
    let task: Task<Value, Error>
  }

  let expired = Emitter<String>()
  let refreshed = Emitter<String>()
  private let entries: LRUCache<String, Entry>
  private let isTransient: @Sendable (any Error) -> Bool
  private let now: @MainActor () -> TimeInterval
  private let schedule: Schedule
  /// The one fetch per path. Removing a path forgets its load, which then stores nothing.
  private var loads: [String: Load] = [:]
  private var lastLoad = 0

  /// - Parameter isTransient: Whether a failed refetch keeps the stale page and retries; any other
  ///   failure drops it.
  nonisolated init(
    maxSize: Int,
    isTransient: @escaping @Sendable (any Error) -> Bool,
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
    self.isTransient = isTransient
    self.now = now
    self.schedule = schedule
  }

  /// The cached page at `path`, refetched behind it when it has expired; else what `fetch` gives,
  /// shared with every caller asking meanwhile, and cached for the lifetime it states.
  func value(for path: String, fetch: @escaping Fetch) async throws -> Value {
    if let cached = entries.get(path) {
      if !isFresh(cached) { _ = load(path, fetch) }
      return cached.value
    }
    return try await load(path, fetch).value
  }

  /// Refetches `path`'s expired page, or joins the refetch in flight, and returns once it settles.
  /// Does nothing for a fresh or uncached page.
  func revalidate(_ path: String, fetch: @escaping Fetch) async {
    if let load = loads[path] {
      _ = try? await load.task.value
    } else if let cached = entries.peek(path), !isFresh(cached) {
      _ = try? await load(path, fetch).value
    }
  }

  /// Whether `path` holds a page whose lifetime has passed. Leaves its recency as it is.
  func isExpired(_ path: String) -> Bool {
    entries.peek(path).map { !isFresh($0) } ?? false
  }

  func remove(_ path: String) {
    loads[path] = nil
    entries.remove(path)?.cancelTimer?()
  }

  func clear() {
    loads.removeAll()
    entries.clear().forEach { $0.cancelTimer?() }
  }

  private func isFresh(_ entry: Entry) -> Bool {
    entry.expiresAt.map { $0 > now() } ?? true
  }

  private func load(_ path: String, _ fetch: @escaping Fetch) -> Task<Value, Error> {
    if let load = loads[path] { return load.task }
    lastLoad += 1
    let id = lastLoad
    let task = Task { @MainActor [weak self] in
      do {
        let fetched = try await fetch()
        self?.settle(path, id) { cache, cached in
          let expiresAt = fetched.lifetime.map { cache.now() + $0 - fetched.age }
          cache.put(path, fetched.value, lifetime: fetched.lifetime, expiresAt: expiresAt, failures: 0, pushIn: expiresAt.map { $0 - cache.now() })
          if cached != nil { cache.refreshed.emit(path) }
        }
        return fetched.value
      } catch {
        self?.settle(path, id) { cache, cached in
          guard let cached, !(error is CancellationError) else { return }
          if cache.isTransient(error) {
            cache.put(path, cached.value, lifetime: cached.lifetime, expiresAt: cached.expiresAt, failures: cached.failures + 1, pushIn: cached.lifetime)
          } else {
            cache.entries.remove(path)?.cancelTimer?()
          }
        }
        throw error
      }
    }
    loads[path] = Load(id: id, task: task)
    return task
  }

  /// Runs `body` with the page cached at `path` when load `id` is still the path's load.
  private func settle(_ path: String, _ id: Int, _ body: (PageCache, Entry?) -> Void) {
    guard loads[path]?.id == id else { return }
    loads[path] = nil
    body(self, entries.peek(path))
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
    let cancelTimer = pushIn.flatMap { delay in
      delay > 0 && failures <= Self.maxRetries ? schedule(delay) { [weak self] in self?.expired.emit(path) } : nil
    }
    let entry = Entry(value: value, lifetime: lifetime, expiresAt: expiresAt, failures: failures, cancelTimer: cancelTimer)
    for dropped in entries.set(path, value: entry) {
      dropped.cancelTimer?()
    }
  }
}
