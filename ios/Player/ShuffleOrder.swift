import Foundation

/// A playback order over queue indices, kept beside the queue so shuffle toggles without
/// reordering it; similar to Media3's ShuffleOrder. Every index in and out is a queue index.
struct ShuffleOrder {
  /// Shuffle position → queue index.
  private(set) var shuffled: [Int]

  /// Queue index → shuffle position; -1 for an index `shuffled` lacks.
  private(set) var indexInShuffled: [Int]

  var count: Int { shuffled.count }

  var isEmpty: Bool { shuffled.isEmpty }

  // MARK: - Initialization

  init() {
    shuffled = []
    indexInShuffled = []
  }

  init(length: Int) {
    shuffled = Self.createShuffledIndices(length: length)

    indexInShuffled = Self.buildIndexInShuffled(from: shuffled)
  }

  /// A random order with `firstIndex` at shuffle position 0. Playback starts on that track, so
  /// left wherever the permutation put it, the queue would end when it is reached: on average
  /// after half the queue, at once when it lands last.
  init(length: Int, firstIndex: Int) {
    self.init(length: length)
    reshuffle(keepingFirst: firstIndex)
  }

  init(shuffledIndices: [Int]) {
    shuffled = shuffledIndices
    indexInShuffled = Self.buildIndexInShuffled(from: shuffledIndices)
  }

  // MARK: - Navigation

  var firstIndex: Int? {
    shuffled.first
  }

  var lastIndex: Int? {
    shuffled.last
  }

  func getNextIndex(after index: Int) -> Int? {
    guard index >= 0, index < indexInShuffled.count else { return nil }
    let shufflePosition = indexInShuffled[index]
    let nextPosition = shufflePosition + 1
    guard nextPosition < shuffled.count else { return nil }
    return shuffled[nextPosition]
  }

  func getPreviousIndex(before index: Int) -> Int? {
    guard index >= 0, index < indexInShuffled.count else { return nil }
    let shufflePosition = indexInShuffled[index]
    let prevPosition = shufflePosition - 1
    guard prevPosition >= 0 else { return nil }
    return shuffled[prevPosition]
  }

  func isLast(_ index: Int) -> Bool {
    guard index >= 0, index < indexInShuffled.count else { return false }
    return indexInShuffled[index] == shuffled.count - 1
  }

  func isFirst(_ index: Int) -> Bool {
    guard index >= 0, index < indexInShuffled.count else { return false }
    return indexInShuffled[index] == 0
  }

  func shufflePosition(of index: Int) -> Int? {
    guard index >= 0, index < indexInShuffled.count else { return nil }
    let position = indexInShuffled[index]
    return position >= 0 ? position : nil
  }

  // MARK: - Mutation

  /// Makes room for `insertionCount` queue items at `insertionIndex`, each at a random shuffle
  /// position.
  mutating func insert(at insertionIndex: Int, count insertionCount: Int) {
    guard insertionCount > 0 else { return }

    for i in 0 ..< shuffled.count {
      if shuffled[i] >= insertionIndex {
        shuffled[i] += insertionCount
      }
    }

    for i in 0 ..< insertionCount {
      let newIndex = insertionIndex + i
      let insertPosition = Int.random(in: 0 ... shuffled.count)
      shuffled.insert(newIndex, at: insertPosition)
    }

    indexInShuffled = Self.buildIndexInShuffled(from: shuffled)
  }

  /// Makes room for one queue item at `insertionIndex`, at shuffle position `position` (clamped).
  /// A queue `move` uses this to keep the item's place in the playback order: a random place
  /// reorders playback, and can put the playing track last, so the queue reports itself over at
  /// the next track end with tracks unplayed.
  mutating func insert(at insertionIndex: Int, atShufflePosition position: Int) {
    for i in 0 ..< shuffled.count where shuffled[i] >= insertionIndex {
      shuffled[i] += 1
    }
    shuffled.insert(insertionIndex, at: min(max(0, position), shuffled.count))
    indexInShuffled = Self.buildIndexInShuffled(from: shuffled)
  }

  mutating func remove(from indexFrom: Int, to indexToExclusive: Int) {
    let removeCount = indexToExclusive - indexFrom
    guard removeCount > 0 else { return }

    shuffled = shuffled.compactMap { index -> Int? in
      if index >= indexFrom, index < indexToExclusive {
        return nil
      } else if index >= indexToExclusive {
        return index - removeCount
      }
      return index
    }

    indexInShuffled = Self.buildIndexInShuffled(from: shuffled)
  }

  mutating func clear() {
    shuffled = []
    indexInShuffled = []
  }

  /// A new random order of the same length; an out-of-range `keepingFirst` or `keepingLast` is
  /// ignored.
  mutating func reshuffle(keepingFirst: Int? = nil, keepingLast: Int? = nil) {
    let length = shuffled.count
    guard length > 0 else { return }

    shuffled = Self.createShuffledIndices(length: length)

    if let first = keepingFirst, first >= 0, first < length {
      if let pos = shuffled.firstIndex(of: first), pos > 0 {
        shuffled.remove(at: pos)
        shuffled.insert(first, at: 0)
      }
    }

    if let last = keepingLast, last >= 0, last < length {
      if let pos = shuffled.firstIndex(of: last), pos < shuffled.count - 1 {
        shuffled.remove(at: pos)
        shuffled.append(last)
      }
    }

    indexInShuffled = Self.buildIndexInShuffled(from: shuffled)
  }

  // MARK: - Private Helpers

  private static func createShuffledIndices(length: Int) -> [Int] {
    var indices = Array(0 ..< length)
    for i in stride(from: length - 1, through: 1, by: -1) {
      let j = Int.random(in: 0 ... i)
      indices.swapAt(i, j)
    }
    return indices
  }

  private static func buildIndexInShuffled(from shuffled: [Int]) -> [Int] {
    var result = [Int](repeating: -1, count: shuffled.count)
    for (position, index) in shuffled.enumerated() {
      if index >= 0, index < result.count {
        result[index] = position
      }
    }
    return result
  }
}
