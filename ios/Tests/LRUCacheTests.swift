import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("LRUCache")
@MainActor
struct LRUCacheTests {
  private final class Box {
    let id: Int
    init(_ id: Int) { self.id = id }
  }

  @Test func evictsTheLeastRecentlyUsed() {
    let cache = LRUCache<String, Int>(maxSize: 2)
    cache.set("a", value: 1)
    cache.set("b", value: 2)
    cache.set("c", value: 3)

    #expect(cache.get("a") == nil)
    #expect(cache.get("b") == 2)
    #expect(cache.get("c") == 3)
    #expect(cache.count == 2)
  }

  @Test func readingPromotesAnEntry() {
    let cache = LRUCache<String, Int>(maxSize: 2)
    cache.set("a", value: 1)
    cache.set("b", value: 2)
    _ = cache.get("a")
    cache.set("c", value: 3)

    #expect(cache.get("a") == 1)
    #expect(cache.get("b") == nil)
  }

  @Test func removeDropsOnlyItsOwnEntry() {
    let cache = LRUCache<String, Int>(maxSize: 3)
    cache.set("a", value: 1)
    cache.set("b", value: 2)
    cache.set("c", value: 3)

    cache.remove("b")

    #expect(cache.get("b") == nil)
    #expect(cache.get("a") == 1)
    #expect(cache.get("c") == 3)
    #expect(cache.count == 2)
  }

  @Test func clearReleasesEveryNode() {
    let cache = LRUCache<String, Box>(maxSize: 10)
    weak var first: Box?
    weak var middle: Box?
    weak var last: Box?

    // The scope ends the test's own references, so only the cache keeps the boxes alive.
    do {
      let a = Box(1), b = Box(2), c = Box(3)
      (first, middle, last) = (a, b, c)
      cache.set("a", value: a)
      cache.set("b", value: b)
      cache.set("c", value: c)
    }

    #expect(first != nil, "the cache should still hold its entries")

    cache.clear()

    #expect(first == nil)
    #expect(middle == nil)
    #expect(last == nil)
    #expect(cache.count == 0)
  }

  @Test func droppingTheCacheReleasesEveryNode() {
    weak var first: Box?
    weak var last: Box?

    do {
      let cache = LRUCache<String, Box>(maxSize: 10)
      let a = Box(1), b = Box(2)
      (first, last) = (a, b)
      cache.set("a", value: a)
      cache.set("b", value: b)
      #expect(first != nil)
    }

    #expect(first == nil)
    #expect(last == nil)
  }

  @Test func evictionReleasesTheEvictedNode() {
    let cache = LRUCache<String, Box>(maxSize: 1)
    weak var evicted: Box?

    do {
      let a = Box(1)
      evicted = a
      cache.set("a", value: a)
      cache.set("b", value: Box(2))
    }

    #expect(evicted == nil)
  }
}
