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

  func value(_ path: String, _ fetch: () async throws -> Fetched<String>) async throws -> String {
    try await cache.value(for: path, fetch: fetch)
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

  @Test func anExpiredPageIsPushedThenRefetched() async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("first", 60) }

    harness.advance(59)
    #expect(try await harness.value("/a") { page("second", 60) } == "first")

    harness.advance(2)
    #expect(harness.pushed == ["/a"])
    #expect(try await harness.value("/a") { page("second", 60) } == "second")
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
    #expect(try await harness.value("/a") { page("second", 60) } == "second")
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
      #expect(try await harness.value("/a") { throw Unreachable() } == "first")
      harness.advance(61)
    }

    #expect(harness.pushed.count == 1 + retries)
    #expect(harness.pendingTimers == 0)
    #expect(try await harness.value("/a") { page("second", 60) } == "second")
  }

  @Test func aVisitWhoseRefetchFailsAfterAnotherStoredAPageGetsThatPage() async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("first", 60) }
    harness.advance(61)

    let failed = try await harness.value("/a") {
      _ = try await harness.value("/a") { page("fresh", 60) }
      throw Unreachable()
    }
    harness.advance(59)

    #expect(failed == "fresh")
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

  @Test func aCancelledRefetchThrowsAndKeepsNoRetry() async throws {
    let harness = Harness()
    _ = try await harness.value("/a") { page("first", 60) }
    harness.advance(61)

    await #expect(throws: CancellationError.self) {
      try await harness.value("/a") { throw CancellationError() }
    }
    harness.advance(61)

    #expect(harness.pushed == ["/a"])
    #expect(harness.cache.isExpired("/a"))
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
    harness.advance(61)
    _ = try await harness.value("/a") { page("second", 60) }
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
