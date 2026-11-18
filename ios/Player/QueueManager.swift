import Foundation
#if canImport(NitroModules)
  import NitroModules
#endif

struct QueueChange: Equatable {
  var tracks = false
  var currentTrack = false

  static let none = QueueChange()
  static let tracksOnly = QueueChange(tracks: true)
  static let tracksAndCurrent = QueueChange(tracks: true, currentTrack: true)
}

enum QueueNavigationResult {
  case trackChanged
  /// A one-track queue on repeat-queue: the caller replays the track if it means to play.
  case sameTrackReplay
  case noChange
}

@MainActor
class QueueManager {
  // MARK: - Stored Properties

  /// -1 when there is no current track.
  private(set) var currentIndex: Int = -1

  private(set) var queueSourcePath: String?

  private(set) var tracks: [Track] = [] {
    didSet {
      Self.lastVersion += 1
      version = Self.lastVersion
    }
  }

  private static var lastVersion = 0

  /// Never repeats in the process, so equal readings mean the same queue even across players.
  private(set) var version = 0

  private(set) var shuffleOrder = ShuffleOrder()

  var repeatMode: RepeatMode = .off

  var shuffleEnabled: Bool = false {
    didSet {
      guard shuffleEnabled, shuffleEnabled != oldValue else { return }
      // Left where the old order put it, a current track near its end would end the queue at once.
      shuffleOrder.reshuffle(keepingFirst: currentIndex)
    }
  }

  // MARK: - Computed Properties

  var currentTrack: Track? {
    guard currentIndex >= 0, currentIndex < tracks.count else { return nil }
    return tracks[currentIndex]
  }

  /// In playback order, so shuffled when shuffle is on; no wrap.
  var nextTracks: [Track] {
    guard currentIndex >= 0, currentIndex < tracks.count else { return [] }

    if shuffleEnabled {
      var result: [Track] = []
      var index = currentIndex
      while let nextIndex = shuffleOrder.getNextIndex(after: index) {
        result.append(tracks[nextIndex])
        index = nextIndex
      }
      return result
    }

    guard currentIndex < tracks.count - 1 else { return [] }
    return Array(tracks[currentIndex + 1 ..< tracks.count])
  }

  /// In playback order, so shuffled when shuffle is on; no wrap.
  var previousTracks: [Track] {
    guard currentIndex >= 0, currentIndex < tracks.count else { return [] }

    if shuffleEnabled {
      var result: [Track] = []
      var index = currentIndex
      while let prevIndex = shuffleOrder.getPreviousIndex(before: index) {
        result.insert(tracks[prevIndex], at: 0)
        index = prevIndex
      }
      return result
    }

    guard currentIndex > 0 else { return [] }
    return Array(tracks[0 ..< currentIndex])
  }

  var isLastInPlaybackOrder: Bool {
    if shuffleEnabled {
      return shuffleOrder.isLast(currentIndex)
    }
    return currentIndex == tracks.count - 1
  }

  var canNext: Bool { nextIndex != nil }

  var canPrevious: Bool { previousIndex != nil }

  // MARK: - Validation

  private func throwIfQueueEmpty() throws {
    if tracks.isEmpty {
      throw TrackPlayerError.QueueError.empty
    }
  }

  private func throwIfIndexInvalid(
    index: Int,
    name: String = "index",
    min: Int? = nil,
    max: Int? = nil,
  ) throws {
    guard index >= (min ?? 0), (max ?? tracks.count) > index else {
      throw TrackPlayerError.QueueError.invalidIndex(
        index: index,
        message: "\(name) must be non-negative and less than \(tracks.count)",
      )
    }
  }

  // MARK: - Navigation (returns QueueNavigationResult)

  // Shuffle wraps whatever the repeat mode, as Media3 does; sequential order wraps only on
  // repeat-queue.
  private var nextIndex: Int? {
    guard currentTrack != nil, tracks.count > 1 else { return nil }
    let candidate: Int? = if shuffleEnabled {
      shuffleOrder.getNextIndex(after: currentIndex) ?? shuffleOrder.firstIndex
    } else if currentIndex + 1 < tracks.count {
      currentIndex + 1
    } else {
      repeatMode == .queue ? 0 : nil
    }
    guard let candidate, candidate != currentIndex else { return nil }
    return candidate
  }

  private var previousIndex: Int? {
    guard currentTrack != nil, tracks.count > 1 else { return nil }
    let candidate: Int? = if shuffleEnabled {
      shuffleOrder.getPreviousIndex(before: currentIndex) ?? shuffleOrder.lastIndex
    } else if currentIndex - 1 >= 0 {
      currentIndex - 1
    } else {
      repeatMode == .queue ? tracks.count - 1 : nil
    }
    guard let candidate, candidate != currentIndex else { return nil }
    return candidate
  }

  func next() -> QueueNavigationResult {
    guard currentTrack != nil, !tracks.isEmpty else { return .noChange }
    if let nextIndex {
      currentIndex = nextIndex
      return .trackChanged
    }
    return tracks.count == 1 && repeatMode == .queue ? .sameTrackReplay : .noChange
  }

  func previous() -> QueueNavigationResult {
    guard currentTrack != nil, !tracks.isEmpty else { return .noChange }
    if let previousIndex {
      currentIndex = previousIndex
      return .trackChanged
    }
    return tracks.count == 1 && repeatMode == .queue ? .sameTrackReplay : .noChange
  }

  @discardableResult
  func skipTo(_ index: Int) throws -> QueueNavigationResult {
    try throwIfQueueEmpty()
    try throwIfIndexInvalid(index: index)
    guard index != currentIndex else { return .noChange }
    currentIndex = index
    return .trackChanged
  }

  // MARK: - Mutations

  @discardableResult
  func setQueue(_ newTracks: [Track], initialIndex: Int = 0, sourcePath: String? = nil) -> QueueChange {
    let clampedIndex = max(0, min(initialIndex, newTracks.count - 1))
    currentIndex = clampedIndex
    queueSourcePath = sourcePath
    tracks = newTracks
    shuffleOrder = ShuffleOrder(length: newTracks.count, firstIndex: clampedIndex)
    return .tracksAndCurrent
  }

  /// `initialIndex` picks the current track only when the queue was empty.
  @discardableResult
  func add(_ newTracks: [Track], initialIndex: Int = 0) -> QueueChange {
    guard !newTracks.isEmpty else { return .none }
    let wasEmpty = tracks.isEmpty
    let insertIndex = tracks.count
    tracks.append(contentsOf: newTracks)
    if wasEmpty { return adoptInitialCurrent(initialIndex) }
    shuffleOrder.insert(at: insertIndex, count: newTracks.count)
    return .tracksOnly
  }

  @discardableResult
  func addAt(_ newTracks: [Track], at index: Int) throws -> QueueChange {
    guard !newTracks.isEmpty else { return .none }
    guard index >= 0, tracks.count >= index else {
      throw TrackPlayerError.QueueError.invalidIndex(
        index: index,
        message: "Index to insert at has to be non-negative and equal to or smaller than the number of tracks: (\(tracks.count))",
      )
    }
    let wasEmpty = tracks.isEmpty
    if currentIndex >= 0, currentIndex >= index {
      currentIndex += newTracks.count
    }
    tracks.insert(contentsOf: newTracks, at: index)
    if wasEmpty { return adoptInitialCurrent(0) }
    shuffleOrder.insert(at: index, count: newTracks.count)
    return .tracksOnly
  }

  // Pins the current track first in the shuffle order, shuffle on or not: inserted at random, it
  // could land last and end the queue after one track.
  private func adoptInitialCurrent(_ index: Int) -> QueueChange {
    currentIndex = max(0, min(index, tracks.count - 1))
    shuffleOrder = ShuffleOrder(length: tracks.count, firstIndex: currentIndex)
    return .tracksAndCurrent
  }

  /// Removing the current track makes the one after it current, wrapping to the first.
  @discardableResult
  func remove(_ index: Int) throws -> QueueChange {
    try throwIfQueueEmpty()
    try throwIfIndexInvalid(index: index)
    tracks.remove(at: index)
    shuffleOrder.remove(from: index, to: index + 1)
    if index == currentIndex {
      currentIndex = tracks.count > 0 ? currentIndex % tracks.count : -1
      return .tracksAndCurrent
    } else if index < currentIndex {
      currentIndex -= 1
    }
    return .tracksOnly
  }

  /// A `toIndex` past the end moves the track to the end.
  @discardableResult
  func move(fromIndex: Int, toIndex: Int) throws -> QueueChange {
    try throwIfQueueEmpty()
    try throwIfIndexInvalid(index: fromIndex, name: "fromIndex")
    try throwIfIndexInvalid(index: toIndex, name: "toIndex", max: Int.max)
    let track = tracks.remove(at: fromIndex)
    let insertion = min(tracks.count, toIndex)
    tracks.insert(track, at: insertion)
    // Keeps the moved track's shuffle position: inserted at random, it reorders shuffled playback.
    let shufflePosition = shuffleOrder.shufflePosition(of: fromIndex)
    shuffleOrder.remove(from: fromIndex, to: fromIndex + 1)
    shuffleOrder.insert(
      at: insertion, atShufflePosition: shufflePosition ?? shuffleOrder.count,
    )
    if currentIndex == fromIndex {
      currentIndex = insertion
    } else if fromIndex < currentIndex, insertion >= currentIndex {
      currentIndex -= 1
    } else if fromIndex > currentIndex, insertion <= currentIndex {
      currentIndex += 1
    }
    return .tracksOnly
  }

  @discardableResult
  func clear() -> QueueChange {
    guard currentIndex != -1 else { return .none }
    currentIndex = -1
    tracks.removeAll()
    shuffleOrder.clear()
    queueSourcePath = nil
    return .tracksAndCurrent
  }

  @discardableResult
  func replace(_ index: Int, _ track: Track) throws -> QueueChange {
    try throwIfIndexInvalid(index: index)
    tracks[index] = track
    return .tracksOnly
  }

  /// Removes the tracks after the current one in queue order, not shuffle order.
  @discardableResult
  func removeUpcomingTracks() -> QueueChange {
    guard !tracks.isEmpty else { return .none }
    let nextIndex = currentIndex + 1
    guard nextIndex < tracks.count else { return .none }
    let end = tracks.count
    tracks.removeSubrange(nextIndex ..< end)
    shuffleOrder.remove(from: nextIndex, to: end)
    return .tracksOnly
  }
}
