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

  /// The value at `key`, leaving its recency as it is.
  func peek(_ key: Key) -> Value? {
    entries[key]
  }

  /// Returns the values it drops: the one `key` held and the evicted one.
  @discardableResult
  func set(_ key: Key, value: Value) -> [Value] {
    var dropped = entries.updateValue(value, forKey: key).map { [$0] } ?? []
    touch(key)
    if order.count > maxSize, let evicted = entries.removeValue(forKey: order.removeFirst()) {
      dropped.append(evicted)
    }
    return dropped
  }

  @discardableResult
  func remove(_ key: Key) -> Value? {
    order.removeAll { $0 == key }
    return entries.removeValue(forKey: key)
  }

  func clear() {
    entries.removeAll()
    order.removeAll()
  }

  func removeAll() -> [Value] {
    defer { clear() }
    return Array(entries.values)
  }

  private func touch(_ key: Key) {
    order.removeAll { $0 == key }
    order.append(key)
  }
}
