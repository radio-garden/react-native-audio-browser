import Foundation

/// A thread-safe Least Recently Used (LRU) cache.
///
/// When the cache exceeds its maximum size, the least recently accessed
/// items are evicted to make room for new entries.
final class LRUCache<Key: Hashable, Value> {
  private let maxSize: Int
  private var cache: [Key: Value] = [:]
  private var accessOrder: [Key] = []
  private let lock = NSLock()

  /// Creates a new LRU cache with the specified maximum size.
  ///
  /// - Parameter maxSize: The maximum number of items the cache can hold
  init(maxSize: Int) {
    self.maxSize = maxSize
  }

  /// Gets a value from the cache, updating its access order.
  ///
  /// - Parameter key: The key to look up
  /// - Returns: The cached value, or nil if not found
  func get(_ key: Key) -> Value? {
    lock.lock()
    defer { lock.unlock() }

    guard let value = cache[key] else {
      return nil
    }

    // Move key to end of access order (most recently used)
    if let index = accessOrder.firstIndex(of: key) {
      accessOrder.remove(at: index)
      accessOrder.append(key)
    }

    return value
  }

  /// Sets a value in the cache.
  ///
  /// If the cache is full, the least recently used item is evicted.
  ///
  /// - Parameters:
  ///   - key: The key to store
  ///   - value: The value to store
  func set(_ key: Key, value: Value) {
    lock.lock()
    defer { lock.unlock() }

    // If key already exists, update and move to end
    if cache[key] != nil {
      cache[key] = value
      if let index = accessOrder.firstIndex(of: key) {
        accessOrder.remove(at: index)
        accessOrder.append(key)
      }
      return
    }

    // Evict oldest if at capacity
    while accessOrder.count >= maxSize {
      let oldest = accessOrder.removeFirst()
      cache.removeValue(forKey: oldest)
    }

    // Add new entry
    cache[key] = value
    accessOrder.append(key)
  }

  /// Removes a value from the cache.
  ///
  /// - Parameter key: The key to remove
  func remove(_ key: Key) {
    lock.lock()
    defer { lock.unlock() }

    cache.removeValue(forKey: key)
    if let index = accessOrder.firstIndex(of: key) {
      accessOrder.remove(at: index)
    }
  }

  /// Clears all entries from the cache.
  func clear() {
    lock.lock()
    defer { lock.unlock() }

    cache.removeAll()
    accessOrder.removeAll()
  }

  /// The current number of items in the cache.
  var count: Int {
    lock.lock()
    defer { lock.unlock() }
    return cache.count
  }

  /// Returns all values in the cache (no particular order guaranteed).
  var values: [Value] {
    lock.lock()
    defer { lock.unlock() }
    return Array(cache.values)
  }

  /// Returns all keys in the cache (no particular order guaranteed).
  var keys: [Key] {
    lock.lock()
    defer { lock.unlock() }
    return Array(cache.keys)
  }
}
