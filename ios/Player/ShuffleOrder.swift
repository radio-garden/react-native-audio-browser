import Foundation

/// Manages a shuffled ordering of indices, similar to Media3's ShuffleOrder.
///
/// The shuffle order maintains a mapping between logical shuffle positions and actual
/// track indices. This allows shuffle to be toggled on/off without reordering the queue.
///
/// Example with 5 tracks and shuffle order [3, 1, 4, 0, 2]:
/// - Shuffle position 0 → track index 3
/// - Shuffle position 1 → track index 1
/// - Shuffle position 2 → track index 4
/// - etc.
struct ShuffleOrder {
  /// Maps shuffle position → track index
  private(set) var shuffled: [Int]

  /// Maps track index → shuffle position (inverse of shuffled)
  private(set) var indexInShuffled: [Int]

  /// The number of items in the shuffle order
  var count: Int { shuffled.count }

  var isEmpty: Bool { shuffled.isEmpty }

  // MARK: - Initialization

  /// Creates an empty shuffle order
  init() {
    shuffled = []
    indexInShuffled = []
  }

  /// Creates a shuffle order for the given length using Fisher-Yates algorithm
  init(length: Int) {
    // Generate shuffled indices using Fisher-Yates
    shuffled = Self.createShuffledIndices(length: length)

    // Build reverse mapping
    indexInShuffled = Self.buildIndexInShuffled(from: shuffled)
  }

  /// Creates a shuffle order with pre-defined indices
  init(shuffledIndices: [Int]) {
    shuffled = shuffledIndices
    indexInShuffled = Self.buildIndexInShuffled(from: shuffledIndices)
  }

  // MARK: - Navigation

  /// Returns the first index in the shuffle order, or nil if empty
  var firstIndex: Int? {
    shuffled.first
  }

  /// Returns the last index in the shuffle order, or nil if empty
  var lastIndex: Int? {
    shuffled.last
  }

  /// Returns the next index after the given index in the shuffle order
  /// - Parameter index: The current track index
  /// - Returns: The next track index, or nil if at the end
  func getNextIndex(after index: Int) -> Int? {
    guard index >= 0, index < indexInShuffled.count else { return nil }
    let shufflePosition = indexInShuffled[index]
    let nextPosition = shufflePosition + 1
    guard nextPosition < shuffled.count else { return nil }
    return shuffled[nextPosition]
  }

  /// Returns the previous index before the given index in the shuffle order
  /// - Parameter index: The current track index
  /// - Returns: The previous track index, or nil if at the beginning
  func getPreviousIndex(before index: Int) -> Int? {
    guard index >= 0, index < indexInShuffled.count else { return nil }
    let shufflePosition = indexInShuffled[index]
    let prevPosition = shufflePosition - 1
    guard prevPosition >= 0 else { return nil }
    return shuffled[prevPosition]
  }

  /// Checks if the given index is the last in the shuffle order
  func isLast(_ index: Int) -> Bool {
    guard index >= 0, index < indexInShuffled.count else { return false }
    return indexInShuffled[index] == shuffled.count - 1
  }

  /// Checks if the given index is the first in the shuffle order
  func isFirst(_ index: Int) -> Bool {
    guard index >= 0, index < indexInShuffled.count else { return false }
    return indexInShuffled[index] == 0
  }

  // MARK: - Mutation

  /// Inserts items at random positions in the shuffle order
  mutating func insert(at insertionIndex: Int, count insertionCount: Int) {
    guard insertionCount > 0 else { return }

    // Adjust existing indices that are >= insertionIndex
    for i in 0 ..< shuffled.count {
      if shuffled[i] >= insertionIndex {
        shuffled[i] += insertionCount
      }
    }

    // Insert new indices at random positions
    for i in 0 ..< insertionCount {
      let newIndex = insertionIndex + i
      let insertPosition = Int.random(in: 0 ... shuffled.count)
      shuffled.insert(newIndex, at: insertPosition)
    }

    // Rebuild reverse mapping
    indexInShuffled = Self.buildIndexInShuffled(from: shuffled)
  }

  /// Removes items in the specified range from the shuffle order
  mutating func remove(from indexFrom: Int, to indexToExclusive: Int) {
    let removeCount = indexToExclusive - indexFrom
    guard removeCount > 0 else { return }

    // Remove indices in the range and adjust remaining indices
    shuffled = shuffled.compactMap { index -> Int? in
      if index >= indexFrom, index < indexToExclusive {
        return nil // Remove this index
      } else if index >= indexToExclusive {
        return index - removeCount // Adjust index
      }
      return index
    }

    // Rebuild reverse mapping
    indexInShuffled = Self.buildIndexInShuffled(from: shuffled)
  }

  /// Clears the shuffle order
  mutating func clear() {
    shuffled = []
    indexInShuffled = []
  }

  /// Reshuffles the order, optionally keeping a specific index first or last
  mutating func reshuffle(keepingFirst: Int? = nil, keepingLast: Int? = nil) {
    let length = shuffled.count
    guard length > 0 else { return }

    shuffled = Self.createShuffledIndices(length: length)

    // If we have a keepingFirst index, move it to the front
    if let first = keepingFirst, first >= 0, first < length {
      if let pos = shuffled.firstIndex(of: first), pos > 0 {
        shuffled.remove(at: pos)
        shuffled.insert(first, at: 0)
      }
    }

    // If we have a keepingLast index, move it to the end
    if let last = keepingLast, last >= 0, last < length {
      if let pos = shuffled.firstIndex(of: last), pos < shuffled.count - 1 {
        shuffled.remove(at: pos)
        shuffled.append(last)
      }
    }

    indexInShuffled = Self.buildIndexInShuffled(from: shuffled)
  }

  // MARK: - Private Helpers

  /// Creates shuffled indices using Fisher-Yates algorithm
  private static func createShuffledIndices(length: Int) -> [Int] {
    var indices = Array(0 ..< length)
    for i in stride(from: length - 1, through: 1, by: -1) {
      let j = Int.random(in: 0 ... i)
      indices.swapAt(i, j)
    }
    return indices
  }

  /// Builds the reverse mapping from track index to shuffle position
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
