import Foundation

/// A thread-safe Least Recently Used (LRU) cache with O(1) operations.
///
/// When the cache exceeds its maximum size, the least recently accessed
/// items are evicted to make room for new entries.
///
/// Uses a doubly-linked list + dictionary for O(1) get/set/remove operations.
final class LRUCache<Key: Hashable, Value> {
  /// Doubly-linked list node for O(1) removal and reordering.
  private final class Node {
    let key: Key
    var value: Value
    var prev: Node?
    var next: Node?

    init(key: Key, value: Value) {
      self.key = key
      self.value = value
    }
  }

  private let maxSize: Int
  private var cache: [Key: Node] = [:]
  private var head: Node?  // Most recently used
  private var tail: Node?  // Least recently used
  private let lock = NSLock()

  /// Creates a new LRU cache with the specified maximum size.
  ///
  /// - Parameter maxSize: The maximum number of items the cache can hold
  init(maxSize: Int) {
    self.maxSize = maxSize
  }

  /// Moves a node to the head (most recently used position).
  private func moveToHead(_ node: Node) {
    guard node !== head else { return }

    // Remove from current position
    node.prev?.next = node.next
    node.next?.prev = node.prev

    if node === tail {
      tail = node.prev
    }

    // Insert at head
    node.prev = nil
    node.next = head
    head?.prev = node
    head = node

    if tail == nil {
      tail = node
    }
  }

  /// Removes a node from the list.
  private func removeNode(_ node: Node) {
    node.prev?.next = node.next
    node.next?.prev = node.prev

    if node === head {
      head = node.next
    }
    if node === tail {
      tail = node.prev
    }

    node.prev = nil
    node.next = nil
  }

  /// Adds a node at the head (most recently used position).
  private func addToHead(_ node: Node) {
    node.prev = nil
    node.next = head
    head?.prev = node
    head = node

    if tail == nil {
      tail = node
    }
  }

  /// Removes and returns the tail node (least recently used).
  private func removeTail() -> Node? {
    guard let tailNode = tail else { return nil }
    removeNode(tailNode)
    return tailNode
  }

  /// Gets a value from the cache, updating its access order.
  ///
  /// - Parameter key: The key to look up
  /// - Returns: The cached value, or nil if not found
  func get(_ key: Key) -> Value? {
    lock.lock()
    defer { lock.unlock() }

    guard let node = cache[key] else {
      return nil
    }

    // Move to head (most recently used)
    moveToHead(node)
    return node.value
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

    // If key exists, update value and move to head
    if let existingNode = cache[key] {
      existingNode.value = value
      moveToHead(existingNode)
      return
    }

    // Evict oldest if at capacity
    if cache.count >= maxSize, let evicted = removeTail() {
      cache.removeValue(forKey: evicted.key)
    }

    // Add new entry at head
    let newNode = Node(key: key, value: value)
    cache[key] = newNode
    addToHead(newNode)
  }

  /// Removes a value from the cache.
  ///
  /// - Parameter key: The key to remove
  func remove(_ key: Key) {
    lock.lock()
    defer { lock.unlock() }

    guard let node = cache[key] else { return }
    removeNode(node)
    cache.removeValue(forKey: key)
  }

  /// Clears all entries from the cache.
  func clear() {
    lock.lock()
    defer { lock.unlock() }

    cache.removeAll()
    head = nil
    tail = nil
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
    return cache.values.map { $0.value }
  }

  /// Returns all keys in the cache (no particular order guaranteed).
  var keys: [Key] {
    lock.lock()
    defer { lock.unlock() }
    return Array(cache.keys)
  }
}
