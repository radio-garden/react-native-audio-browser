/// A cache of at most `maxSize` entries that evicts the least recently used one.
/// Sized for a handful of entries: a use moves the key within an array.
@MainActor
final class LRUCache<Key: Hashable, Value> {
  private let maxSize: Int
  private var entries: [Key: Value] = [:]
  /// Least recently used first.
  private var order: [Key] = []

  nonisolated init(maxSize: Int) {
    self.maxSize = maxSize
  }

  var count: Int {
    entries.count
  }

  func get(_ key: Key) -> Value? {
    guard let value = entries[key] else { return nil }
    touch(key)
    return value
  }

  func set(_ key: Key, value: Value) {
    entries[key] = value
    touch(key)
    if order.count > maxSize {
      entries.removeValue(forKey: order.removeFirst())
    }
  }

  func remove(_ key: Key) {
    entries.removeValue(forKey: key)
    order.removeAll { $0 == key }
  }

  func clear() {
    entries.removeAll()
    order.removeAll()
  }

  private func touch(_ key: Key) {
    order.removeAll { $0 == key }
    order.append(key)
  }
}
