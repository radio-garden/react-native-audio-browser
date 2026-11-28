import Foundation
import Testing

@testable import AudioBrowserTestable

private struct Unreachable: Error {}

/// A page cache on a clock the test moves, whose timers run when `advance` passes them.
@MainActor
private final class Harness {
  var now: TimeInterval = 0
  var pushed: [String] = []
  private var timers: [Int: (at: TimeInterval, work: @MainActor () -> Void)] = [:]
  private var nextTimer = 0
  lazy var cache = PageCache<String>(
    maxSize: 2,
    now: { [unowned self] in now },
    schedule: { [unowned self] delay, work in
      nextTimer += 1
      let id = nextTimer
      timers[id] = (now + delay, work)
      return { [weak self] in self?.timers[id] = nil }
    },
  )
  private var subscription: Subscription?

  var pendingTimers: Int {
    timers.count
  }

  init() {
    subscription = cache.expired.subscribe { [unowned self] in pushed.append($0) }
  }

  func advance(_ seconds: TimeInterval) {
    now += seconds
    while let (id, timer) = timers.filter({ $0.value.at <= now }).min(by: { $0.value.at < $1.value.at }) {
      timers[id] = nil
      timer.work()
    }
  }

  func value(
    _ path: String,
    useCache: Bool = true,
    _ fetch: () async throws -> Fetched<String>,
  ) async throws -> String {
    try await cache.value(for: path, useCache: useCache, fetch: fetch)
  }
}

private func page(_ value: String, _ lifetime: TimeInterval? = nil) -> Fetched<String> {
  Fetched(value: value, lifetime: lifetime)
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

  @Test func anExpiredPageIsPushedThenRefetched() async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("first", 60) }

    harness.advance(59)
    #expect(try await harness.value("/a") { page("second", 60) } == "first")

    harness.advance(2)
    #expect(harness.pushed == ["/a"])
    #expect(try await harness.value("/a") { page("second", 60) } == "second")
  }

  @Test func aFailedRefetchKeepsTheStalePageAndIsRetriedALifetimeLater() async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("first", 60) }
    harness.advance(61)

    let stale = try await harness.value("/a") { throw Unreachable() }
    harness.advance(61)

    #expect(stale == "first")
    #expect(harness.pushed == ["/a", "/a"])
  }

  @Test func aPageStopsBeingPushedAfterItsLastRetryFails() async throws {
    let harness = Harness()
    let retries = PageCache<String>.maxRetries
    _ = try await harness.value("/a") { page("first", 60) }
    harness.advance(61)

    for _ in 0 ... retries {
      await #expect(throws: Unreachable.self) {
        try await harness.value("/a", useCache: false) { throw Unreachable() }
      }
      harness.advance(61)
    }

    #expect(harness.pushed.count == 1 + retries)
    #expect(harness.pendingTimers == 0)
    #expect(try await harness.value("/a") { page("second", 60) } == "second")
  }

  @Test func aFailedRefreshThrowsAndTheNextVisitStillGetsTheStalePage() async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("first", 60) }
    harness.advance(61)

    await #expect(throws: Unreachable.self) {
      try await harness.value("/a", useCache: false) { throw Unreachable() }
    }
    #expect(try await harness.value("/a") { throw Unreachable() } == "first")
  }

  @Test func aFailedRefreshOfAFreshPageLeavesItsExpiry() async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("first", 60) }
    harness.advance(50)

    for _ in 0 ... PageCache<String>.maxRetries {
      await #expect(throws: Unreachable.self) {
        try await harness.value("/a", useCache: false) { throw Unreachable() }
      }
    }
    harness.advance(11)

    #expect(harness.pushed == ["/a"])
  }

  @Test func aFailedRefreshLeavesAPageStoredMeanwhileInPlace() async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("first", 60) }
    harness.advance(61)

    await #expect(throws: Unreachable.self) {
      try await harness.value("/a", useCache: false) {
        _ = try await harness.value("/a", useCache: false) { page("fresh", 60) }
        throw Unreachable()
      }
    }
    harness.advance(59)

    #expect(try await harness.value("/a") { throw Unreachable() } == "fresh")
    #expect(harness.pushed == ["/a"])
  }

  @Test(arguments: [true, false])
  func aStalePageRemovedDuringAFailedRefetchIsNotReturned(clearingAll: Bool) async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("first", 60) }
    harness.advance(61)

    await #expect(throws: Unreachable.self) {
      try await harness.value("/a") {
        if clearingAll { harness.cache.clear() } else { harness.cache.remove("/a") }
        throw Unreachable()
      }
    }
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
    _ = try await harness.value("/a") { page("first", 60) }
    harness.advance(30)
    _ = try await harness.value("/a", useCache: false) { page("second", 60) }

    #expect(harness.pendingTimers == 1)
    harness.advance(31)
    #expect(harness.pushed.isEmpty)
    harness.advance(30)
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

    _ = try await harness.value("/c") { page("c") }

    #expect(try await harness.value("/b") { page("refetched") } == "b")
  }

  @Test(arguments: [true, false])
  func aFetchThatOutlivesARemovalCachesNothing(clearingAll: Bool) async throws {
    let harness = Harness()
    _ = try await harness.value("/a") {
      if clearingAll { harness.cache.clear() } else { harness.cache.remove("/a") }
      return page("old")
    }

    #expect(try await harness.value("/a") { page("new") } == "new")
  }
}
