import Foundation
#if canImport(NitroModules)
  import NitroModules
#endif

/// What a queue mutation changed.
struct QueueChange: Equatable {
  var tracks = false
  /// A different track became current, or the current track was cleared.
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

/// The queue's tracks, current index, shuffle and repeat, with no knowledge of playback.
@MainActor
class QueueManager {
  // MARK: - Stored Properties

  /// -1 when there is no current track.
  private(set) var currentIndex: Int = -1

  /// The path the queue was expanded from, if any.
  private(set) var queueSourcePath: String?

  private(set) var tracks: [Track] = []

  private(set) var shuffleOrder = ShuffleOrder()

  var repeatMode: RepeatMode = .off

  var shuffleEnabled: Bool = false {
    didSet {
      // Re-pin on enable: the current track can sit anywhere in the old order, and near its end
      // turning shuffle on would end the queue at once.
      guard shuffleEnabled, shuffleEnabled != oldValue else { return }
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

  /// Whether `next()` moves to a different track; drives the remote next button.
  var canNext: Bool { nextIndex != nil }

  /// Whether `previous()` moves to a different track; drives the remote previous button.
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

  // Shared by `next()` and `canNext` so the button and the navigation agree. Shuffle wraps
  // whatever the repeat mode, as Media3 does; sequential order wraps only on repeat-queue.
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

  /// Clamps `initialIndex` into the new tracks.
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

  // Also pins the current track first in the shuffle order, shuffle on or not: an inserted index
  // lands at a random shuffle position, and landing last would end the queue after one track.
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

  /// A `toIndex` past the end moves the track to the end. The current track stays current.
  @discardableResult
  func move(fromIndex: Int, toIndex: Int) throws -> QueueChange {
    try throwIfQueueEmpty()
    try throwIfIndexInvalid(index: fromIndex, name: "fromIndex")
    try throwIfIndexInvalid(index: toIndex, name: "toIndex", max: Int.max)
    let track = tracks.remove(at: fromIndex)
    let insertion = min(tracks.count, toIndex)
    tracks.insert(track, at: insertion)
    // Keep the moved track's shuffle position: a plain insert lands it at random, reordering
    // shuffled playback and possibly putting the playing track last.
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

  /// Does nothing when `currentIndex` is already -1.
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
