import Foundation
import Testing

@testable import AudioBrowserTestable

private struct Unreachable: Error {}

/// A page cache on a clock the test moves, whose expiry timers run only when the test says so.
@MainActor
private final class Harness {
  var now = Date(timeIntervalSince1970: 0)
  var pushed: [String] = []
  private var timers: [CheckedContinuation<Void, Never>] = []
  lazy var cache = PageCache<String>(
    maxSize: 2,
    now: { [weak self] in self?.now ?? .distantFuture },
    sleep: { [weak self] _ in await withCheckedContinuation { self?.timers.append($0) ?? $0.resume() } },
  )
  private var subscription: Subscription?

  init() {
    subscription = cache.expired.subscribe { [unowned self] in pushed.append($0) }
  }

  func advance(_ seconds: TimeInterval) async {
    now += seconds
    let due = timers
    timers = []
    due.forEach { $0.resume() }
    for _ in 0 ..< 10 {
      await Task.yield()
    }
  }
}

@MainActor
@Suite("PageCache")
struct PageCacheTests {
  @Test func aPageWithNoLifetimeIsServedFromTheCacheForever() async throws {
    let harness = Harness()
    _ = try await harness.cache.value(for: "/a", useCache: true) { ("first", nil) }
    await harness.advance(1_000_000)

    let value = try await harness.cache.value(for: "/a", useCache: true) { ("second", nil) }

    #expect(value == "first")
    #expect(harness.pushed.isEmpty)
  }

  @Test func anExpiredPageIsPushedThenRefetched() async throws {
    let harness = Harness()
    _ = try await harness.cache.value(for: "/a", useCache: true) { ("first", 60) }

    await harness.advance(59)
    #expect(try await harness.cache.value(for: "/a", useCache: true) { ("second", 60) } == "first")

    await harness.advance(2)
    #expect(harness.pushed == ["/a"])
    #expect(try await harness.cache.value(for: "/a", useCache: true) { ("second", 60) } == "second")
  }

  @Test func aFailedRefetchOfAnExpiredPageKeepsItAndRetriesALifetimeLater() async throws {
    let harness = Harness()
    _ = try await harness.cache.value(for: "/a", useCache: true) { ("first", 60) }
    await harness.advance(61)

    let stale = try await harness.cache.value(for: "/a", useCache: true) { throw Unreachable() }
    await harness.advance(61)

    #expect(stale == "first")
    #expect(harness.pushed == ["/a", "/a"])
  }

  @Test func aFailedRefreshThrowsAndKeepsThePageForTheNextVisit() async throws {
    let harness = Harness()
    _ = try await harness.cache.value(for: "/a", useCache: true) { ("first", 60) }
    await harness.advance(61)

    await #expect(throws: Unreachable.self) {
      try await harness.cache.value(for: "/a", useCache: false) { throw Unreachable() }
    }
    #expect(try await harness.cache.value(for: "/a", useCache: true) { throw Unreachable() } == "first")
  }

  @Test func aPageWithNoLifetimeLeftIsNotCached() async throws {
    let harness = Harness()
    _ = try await harness.cache.value(for: "/a", useCache: true) { ("first", 0) }

    #expect(try await harness.cache.value(for: "/a", useCache: true) { ("second", 0) } == "second")
    await harness.advance(1)
    #expect(harness.pushed.isEmpty)
  }

  @Test(arguments: [true, false])
  func aRemovedPageIsNeverPushed(clearingAll: Bool) async throws {
    let harness = Harness()
    _ = try await harness.cache.value(for: "/a", useCache: true) { ("first", 60) }

    if clearingAll { harness.cache.clear() } else { harness.cache.remove("/a") }
    await harness.advance(61)

    #expect(harness.pushed.isEmpty)
  }

  @Test func aReplacedPageIsPushedAtItsOwnExpiryOnly() async throws {
    let harness = Harness()
    _ = try await harness.cache.value(for: "/a", useCache: true) { ("first", 60) }
    await harness.advance(30)
    _ = try await harness.cache.value(for: "/a", useCache: false) { ("second", 60) }

    await harness.advance(31)
    #expect(harness.pushed.isEmpty)
    await harness.advance(30)
    #expect(harness.pushed == ["/a"])
  }

  @Test func anEvictedPageIsNeverPushed() async throws {
    let harness = Harness()
    _ = try await harness.cache.value(for: "/a", useCache: true) { ("a", 60) }
    _ = try await harness.cache.value(for: "/b", useCache: true) { ("b", nil) }
    _ = try await harness.cache.value(for: "/c", useCache: true) { ("c", nil) }

    await harness.advance(61)

    #expect(harness.pushed.isEmpty)
  }

  @Test(arguments: [true, false])
  func aFetchThatOutlivesARemovalCachesNothing(clearingAll: Bool) async throws {
    let harness = Harness()
    _ = try await harness.cache.value(for: "/a", useCache: true) {
      if clearingAll { harness.cache.clear() } else { harness.cache.remove("/a") }
      return ("old", nil)
    }

    #expect(try await harness.cache.value(for: "/a", useCache: true) { ("new", nil) } == "new")
  }
}
