import Foundation
import Testing

@testable import AudioBrowserTestable

/// A failure a refetch may survive, as a network error or a 5xx is.
private struct Unreachable: Error {}
/// A failure that ends a page, as a 404 or an undecodable body does.
private struct NotFound: Error {}

/// Holds a fetch until the test opens it.
@MainActor
private final class Gate {
  private var opened = false
  private var waiters: [CheckedContinuation<Void, Never>] = []

  func wait() async {
    guard !opened else { return }
    await withCheckedContinuation { waiters.append($0) }
  }

  func open() {
    opened = true
    waiters.forEach { $0.resume() }
    waiters = []
  }
}

/// A page cache on a clock the test moves, whose timers run when `advance` passes them.
@MainActor
private final class Harness {
  var now: TimeInterval = 0
  var pushed: [String] = []
  var refreshed: [String] = []
  private var timers: [Int: (at: TimeInterval, work: @MainActor () -> Void)] = [:]
  private var nextTimer = 0
  lazy var cache = PageCache<String>(
    maxSize: 2,
    isTransient: { $0 is Unreachable },
    now: { [unowned self] in now },
    schedule: { [unowned self] delay, work in
      nextTimer += 1
      let id = nextTimer
      timers[id] = (now + delay, work)
      return { [weak self] in self?.timers[id] = nil }
    },
  )
  private var subscriptions: [Subscription] = []

  var pendingTimers: Int {
    timers.count
  }

  init() {
    subscriptions = [
      cache.expired.subscribe { [unowned self] in pushed.append($0) },
      cache.refreshed.subscribe { [unowned self] in refreshed.append($0) },
    ]
  }

  func advance(_ seconds: TimeInterval) {
    now += seconds
    while let (id, timer) = timers.filter({ $0.value.at <= now }).min(by: { $0.value.at < $1.value.at }) {
      timers[id] = nil
      timer.work()
    }
  }

  func value(_ path: String, _ fetch: @escaping @MainActor () async throws -> Fetched<String>) async throws -> String {
    try await cache.value(for: path, fetch: fetch)
  }

  /// Waits for the refetch in flight for `path`.
  func settle(_ path: String) async {
    await cache.revalidate(path) {
      Issue.record("no refetch in flight for \(path)")
      throw Unreachable()
    }
  }

  /// Lets a refetch no caller can join any more run to its end.
  func drain() async {
    for _ in 0 ..< 20 {
      await Task.yield()
    }
  }

  /// `path` cached with a 60 s lifetime that has passed.
  func expiredPage(_ path: String, _ value: String = "first") async throws {
    _ = try await self.value(path) { page(value, 60) }
    advance(61)
  }
}

private func page(_ value: String, _ lifetime: TimeInterval? = nil, age: TimeInterval = 0) -> Fetched<String> {
  Fetched(value: value, lifetime: lifetime, age: age)
}

@MainActor
@Suite("PageCache")
struct PageCacheTests {
  @Test func aPageWithNoLifetimeIsServedFromTheCacheForever() async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("first") }
    harness.advance(1_000_000)

    #expect(try await harness.value("/a") { page("second") } == "first")
    #expect(harness.pushed.isEmpty)
  }

  @Test func anExpiredPageIsPushedThenServedAtOnceAndRefetchedBehindIt() async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("first", 60) }
    harness.advance(59)
    #expect(harness.pushed.isEmpty)
    harness.advance(2)
    #expect(harness.pushed == ["/a"])

    let gate = Gate()
    #expect(try await harness.value("/a") {
      await gate.wait()
      return page("second", 60)
    } == "first")
    #expect(harness.refreshed.isEmpty)

    gate.open()
    await harness.settle("/a")
    #expect(harness.refreshed == ["/a"])
    #expect(try await harness.value("/a") { page("third") } == "second")
  }

  @Test func everyVisitDuringARefetchSharesIt() async throws {
    let harness = Harness()
    try await harness.expiredPage("/a")
    let gate = Gate()
    var fetches = 0

    for _ in 0 ..< 3 {
      _ = try await harness.value("/a") {
        fetches += 1
        await gate.wait()
        return page("second", 60)
      }
    }
    gate.open()
    await harness.settle("/a")

    #expect(fetches == 1)
    #expect(harness.refreshed == ["/a"])
  }

  @Test func firstVisitsMadeTogetherShareOneFetch() async throws {
    let harness = Harness()
    let gate = Gate()
    var fetches = 0
    let first = Task { @MainActor in
      try await harness.value("/a") {
        fetches += 1
        await gate.wait()
        return page("a")
      }
    }
    while fetches == 0 {
      await Task.yield()
    }
    let second = Task { @MainActor in
      try await harness.value("/a") {
        fetches += 1
        return page("b")
      }
    }
    await harness.drain()
    gate.open()

    #expect(try await first.value == "a")
    #expect(try await second.value == "a")
    #expect(fetches == 1)
  }

  @Test func aFirstFetchThatFailsThrows() async throws {
    let harness = Harness()
    await #expect(throws: Unreachable.self) {
      try await harness.value("/a") { throw Unreachable() }
    }
  }

  @Test func theAgeAPageArrivedWithShortensItsLifetime() async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("first", 60, age: 40) }

    harness.advance(21)

    #expect(harness.pushed == ["/a"])
    #expect(harness.cache.isExpired("/a"))
  }

  @Test func aPageWhoseAgeUsedUpItsLifetimeIsExpiredWithNoPush() async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("first", 60, age: 100) }

    #expect(harness.cache.isExpired("/a"))
    #expect(harness.pendingTimers == 0)
    #expect(try await harness.value("/a") { page("second", 60) } == "first")
    await harness.settle("/a")
    #expect(try await harness.value("/a") { page("third", 60) } == "second")
  }

  @Test func isExpiredHoldsOnlyForACachedPageWhoseLifetimePassed() async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("a", 60) }
    _ = try await harness.value("/b") { page("b") }

    #expect(!harness.cache.isExpired("/a"))
    harness.advance(61)
    #expect(harness.cache.isExpired("/a"))
    #expect(!harness.cache.isExpired("/b"))
    #expect(!harness.cache.isExpired("/missing"))
  }

  @Test func aRefetchThatFailsInTransitKeepsThePageUnrefreshedAndIsRetriedALifetimeLater() async throws {
    let harness = Harness()
    try await harness.expiredPage("/a")

    #expect(try await harness.value("/a") { throw Unreachable() } == "first")
    await harness.settle("/a")
    harness.advance(61)

    #expect(harness.refreshed.isEmpty)
    #expect(harness.pushed == ["/a", "/a"])
    #expect(try await harness.value("/a") { throw Unreachable() } == "first")
  }

  @Test func aPageStopsBeingPushedAfterItsLastRetryFails() async throws {
    let harness = Harness()
    let retries = PageCache<String>.maxRetries
    try await harness.expiredPage("/a")

    for _ in 0 ... retries {
      _ = try await harness.value("/a") { throw Unreachable() }
      await harness.settle("/a")
      harness.advance(61)
    }

    #expect(harness.pushed.count == 1 + retries)
    #expect(harness.pendingTimers == 0)
  }

  @Test func aPageWhoseRefetchIsRefusedIsDropped() async throws {
    let harness = Harness()
    try await harness.expiredPage("/a")

    #expect(try await harness.value("/a") { throw NotFound() } == "first")
    await harness.settle("/a")

    #expect(harness.pendingTimers == 0)
    #expect(harness.refreshed.isEmpty)
    await #expect(throws: NotFound.self) {
      try await harness.value("/a") { throw NotFound() }
    }
  }

  @Test func aCancelledRefetchLeavesThePageAsItWas() async throws {
    let harness = Harness()
    try await harness.expiredPage("/a")

    _ = try await harness.value("/a") { throw CancellationError() }
    await harness.settle("/a")
    harness.advance(61)

    #expect(harness.pushed == ["/a"])
    #expect(try await harness.value("/a") { throw Unreachable() } == "first")
  }

  @Test(arguments: [true, false])
  func aStalePageRemovedDuringAFailedRefetchIsNotKept(clearingAll: Bool) async throws {
    let harness = Harness()
    try await harness.expiredPage("/a")

    _ = try await harness.value("/a") {
      if clearingAll { harness.cache.clear() } else { harness.cache.remove("/a") }
      throw Unreachable()
    }
    await harness.drain()

    #expect(try await harness.value("/a") { page("new") } == "new")
  }

  @Test(arguments: [true, false])
  func aFetchThatOutlivesARemovalCachesNothing(clearingAll: Bool) async throws {
    let harness = Harness()
    #expect(try await harness.value("/a") {
      if clearingAll { harness.cache.clear() } else { harness.cache.remove("/a") }
      return page("old")
    } == "old")

    #expect(try await harness.value("/a") { page("new") } == "new")
  }

  @Test(arguments: [true, false])
  func aRemovedPageIsNeverPushed(clearingAll: Bool) async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("first", 60) }

    if clearingAll { harness.cache.clear() } else { harness.cache.remove("/a") }

    #expect(harness.pendingTimers == 0)
    harness.advance(61)
    #expect(harness.pushed.isEmpty)
  }

  @Test func aReplacedPageIsPushedAtItsOwnExpiryOnly() async throws {
    let harness = Harness()
    try await harness.expiredPage("/a")
    _ = try await harness.value("/a") { page("second", 60) }
    await harness.settle("/a")
    harness.pushed = []

    #expect(harness.pendingTimers == 1)
    harness.advance(59)
    #expect(harness.pushed.isEmpty)
    harness.advance(2)
    #expect(harness.pushed == ["/a"])
  }

  @Test func anEvictedPageIsNeverPushed() async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("a", 60) }
    _ = try await harness.value("/b") { page("b") }
    _ = try await harness.value("/c") { page("c") }

    #expect(harness.pendingTimers == 0)
    harness.advance(61)
    #expect(harness.pushed.isEmpty)
  }

  @Test func anExpiringPageKeepsItsPlaceInTheEvictionOrder() async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("a", 60) }
    _ = try await harness.value("/b") { page("b") }
    harness.advance(61)
    _ = harness.cache.isExpired("/a")

    _ = try await harness.value("/c") { page("c") }

    #expect(try await harness.value("/b") { page("refetched") } == "b")
  }
}
