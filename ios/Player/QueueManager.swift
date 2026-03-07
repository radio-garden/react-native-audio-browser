import Foundation
#if canImport(NitroModules)
import NitroModules
#endif

/// Notifies the owner when the track list changes.
@MainActor protocol QueueManagerDelegate: AnyObject {
  func queueDidChangeTracks(_ tracks: [Track])
}

/// Result of a navigation action (next/previous/skipTo).
enum QueueNavigationResult {
  /// A different track was selected as current.
  case trackChanged
  /// Single-track queue with repeat-queue — caller decides whether to replay based on playWhenReady.
  case sameTrackReplay
  /// Nothing happened (boundary without wrap, or guards failed).
  case noChange
}

/// Pure queue logic: tracks, indices, shuffle, repeat.
/// Has no knowledge of AVPlayer or playback — TrackPlayer owns that.
@MainActor
class QueueManager {
  weak var delegate: QueueManagerDelegate?

  // MARK: - Stored Properties

  /// The index of the current track. `-1` when there is no current track.
  private(set) var currentIndex: Int = -1

  /// The source path from which the current queue was expanded (e.g., from a contextual URL).
  private(set) var queueSourcePath: String?

  /// All tracks held by the queue.
  private(set) var tracks: [Track] = [] {
    didSet {
      delegate?.queueDidChangeTracks(tracks)
    }
  }

  /// The shuffle order for randomized playback.
  private var shuffleOrder = ShuffleOrder()

  /// The repeat mode for the queue. Plain property — no side effects.
  var repeatMode: RepeatMode = .off

  /// Whether shuffle mode is enabled. Plain property — no side effects.
  var shuffleEnabled: Bool = false

  // MARK: - Computed Properties

  var currentTrack: Track? {
    guard currentIndex >= 0, currentIndex < tracks.count else { return nil }
    return tracks[currentIndex]
  }

  /// The upcoming tracks in playback order.
  /// When shuffle is enabled, returns tracks in shuffled order.
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

  /// The previous tracks in playback order.
  /// When shuffle is enabled, returns tracks in shuffled order.
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

  /// Whether the current track is the last track in playback order.
  var isLastInPlaybackOrder: Bool {
    if shuffleEnabled {
      return shuffleOrder.isLast(currentIndex)
    }
    return currentIndex == tracks.count - 1
  }

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
    max: Int? = nil
  ) throws {
    guard index >= (min ?? 0), (max ?? tracks.count) > index else {
      throw TrackPlayerError.QueueError.invalidIndex(
        index: index,
        message: "\(name) must be non-negative and less than \(tracks.count)"
      )
    }
  }

  // MARK: - Navigation (returns QueueNavigationResult)

  /// Step to the next track in the queue.
  func next() -> QueueNavigationResult {
    guard currentTrack != nil, !tracks.isEmpty else { return .noChange }

    if tracks.count == 1 {
      return repeatMode == .queue ? .sameTrackReplay : .noChange
    }

    var newIndex: Int?
    if shuffleEnabled {
      // Use shuffle order for navigation
      newIndex = shuffleOrder.getNextIndex(after: currentIndex)
      if newIndex == nil {
        // Wrap to start of shuffle order unconditionally (same order, like Media3)
        newIndex = shuffleOrder.firstIndex
      }
    } else {
      // Sequential navigation
      let nextIdx = currentIndex + 1
      if nextIdx < tracks.count {
        newIndex = nextIdx
      } else if repeatMode == .queue {
        newIndex = 0
      }
    }

    if let newIndex, newIndex != currentIndex {
      currentIndex = newIndex
      return .trackChanged
    }
    return .noChange
  }

  /// Step to the previous track in the queue.
  func previous() -> QueueNavigationResult {
    guard currentTrack != nil, !tracks.isEmpty else { return .noChange }

    if tracks.count == 1 {
      return repeatMode == .queue ? .sameTrackReplay : .noChange
    }

    var newIndex: Int?
    if shuffleEnabled {
      // Use shuffle order for navigation
      newIndex = shuffleOrder.getPreviousIndex(before: currentIndex)
      if newIndex == nil {
        // Wrap to end of shuffle order unconditionally (same order, like Media3)
        newIndex = shuffleOrder.lastIndex
      }
    } else {
      // Sequential navigation
      let prevIdx = currentIndex - 1
      if prevIdx >= 0 {
        newIndex = prevIdx
      } else if repeatMode == .queue {
        newIndex = tracks.count - 1
      }
    }

    if let newIndex, newIndex != currentIndex {
      currentIndex = newIndex
      return .trackChanged
    }
    return .noChange
  }

  /// Skip to a specific track in the queue.
  @discardableResult
  func skipTo(_ index: Int) throws -> QueueNavigationResult {
    try throwIfQueueEmpty()
    try throwIfIndexInvalid(index: index)
    guard index != currentIndex else { return .noChange }
    currentIndex = index
    return .trackChanged
  }

  // MARK: - Mutations (returns Bool: whether current track changed)

  /// Replace the entire queue with new tracks.
  /// Returns `true` (current track always changes).
  @discardableResult
  func setQueue(_ newTracks: [Track], initialIndex: Int = 0, sourcePath: String? = nil) -> Bool {
    let clampedIndex = max(0, min(initialIndex, newTracks.count - 1))
    currentIndex = clampedIndex
    queueSourcePath = sourcePath
    tracks = newTracks
    shuffleOrder = ShuffleOrder(length: newTracks.count)
    return true
  }

  /// Add tracks to the end of the queue.
  /// Returns `true` if the queue was empty (a new current track was set).
  @discardableResult
  func add(_ newTracks: [Track], initialIndex: Int = 0) -> Bool {
    guard !newTracks.isEmpty else { return false }
    let wasEmpty = tracks.isEmpty
    let insertIndex = tracks.count
    tracks.append(contentsOf: newTracks)
    shuffleOrder.insert(at: insertIndex, count: newTracks.count)
    if wasEmpty {
      currentIndex = max(0, min(initialIndex, tracks.count - 1))
      return true
    }
    return false
  }

  /// Add tracks at a specific index in the queue.
  /// Returns `true` if the queue was empty (a new current track was set).
  @discardableResult
  func addAt(_ newTracks: [Track], at index: Int) throws -> Bool {
    guard !newTracks.isEmpty else { return false }
    guard index >= 0, tracks.count >= index else {
      throw TrackPlayerError.QueueError.invalidIndex(
        index: index,
        message: "Index to insert at has to be non-negative and equal to or smaller than the number of tracks: (\(tracks.count))"
      )
    }
    let wasEmpty = tracks.isEmpty
    // Correct index when tracks were inserted in front of it:
    if tracks.count > 1, currentIndex >= index {
      currentIndex += newTracks.count
    }
    tracks.insert(contentsOf: newTracks, at: index)
    shuffleOrder.insert(at: index, count: newTracks.count)
    if wasEmpty {
      currentIndex = 0
      return true
    }
    return false
  }

  /// Remove a track from the queue.
  /// Returns `true` if the current track changed.
  @discardableResult
  func remove(_ index: Int) throws -> Bool {
    try throwIfQueueEmpty()
    try throwIfIndexInvalid(index: index)
    tracks.remove(at: index)
    shuffleOrder.remove(from: index, to: index + 1)
    if index == currentIndex {
      currentIndex = tracks.count > 0 ? currentIndex % tracks.count : -1
      return true
    } else if index < currentIndex {
      currentIndex -= 1
    }
    return false
  }

  /// Move a track in the queue from one position to another.
  /// Returns `true` if the current track changed (fromIndex was currentIndex).
  @discardableResult
  func move(fromIndex: Int, toIndex: Int) throws -> Bool {
    try throwIfQueueEmpty()
    try throwIfIndexInvalid(index: fromIndex, name: "fromIndex")
    try throwIfIndexInvalid(index: toIndex, name: "toIndex", max: Int.max)
    // Mutate a copy and assign once to trigger didSet only once
    var newTracks = tracks
    let track = newTracks.remove(at: fromIndex)
    newTracks.insert(track, at: min(newTracks.count, toIndex))
    tracks = newTracks
    if fromIndex == currentIndex {
      currentIndex = toIndex
      return true
    }
    return false
  }

  /// Remove all tracks and reset state.
  /// Returns `true` if there was a current track to clear.
  @discardableResult
  func clear() -> Bool {
    guard currentIndex != -1 else { return false }
    currentIndex = -1
    tracks.removeAll()
    queueSourcePath = nil
    return true
  }

  // MARK: - Other Mutations

  /// Replace the track at a specific index.
  func replace(_ index: Int, _ track: Track) {
    tracks[index] = track
  }

  /// Remove all upcoming tracks (those after currentIndex).
  func removeUpcomingTracks() {
    guard !tracks.isEmpty else { return }
    let nextIndex = currentIndex + 1
    guard nextIndex < tracks.count else { return }
    tracks.removeSubrange(nextIndex ..< tracks.count)
  }
}
