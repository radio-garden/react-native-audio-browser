import Foundation
#if canImport(NitroModules)
  import NitroModules
#endif

/// What a queue mutation changed, reported once the queue has settled.
struct QueueChange: Equatable {
  /// The track list changed.
  var tracks = false
  /// A different track became current, or the current track was cleared.
  var currentTrack = false

  static let none = QueueChange()
  static let tracksOnly = QueueChange(tracks: true)
  static let tracksAndCurrent = QueueChange(tracks: true, currentTrack: true)
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
/// Has no knowledge of AVPlayer or playback — Player and its engine own that.
@MainActor
class QueueManager {
  // MARK: - Stored Properties

  /// The index of the current track. `-1` when there is no current track.
  private(set) var currentIndex: Int = -1

  /// The source path from which the current queue was expanded (e.g., from a contextual URL).
  private(set) var queueSourcePath: String?

  /// All tracks held by the queue.
  private(set) var tracks: [Track] = []

  /// The shuffle order for randomized playback.
  private(set) var shuffleOrder = ShuffleOrder()

  /// The repeat mode for the queue. Plain property — no side effects.
  var repeatMode: RepeatMode = .off

  /// Whether shuffle mode is enabled.
  var shuffleEnabled: Bool = false {
    didSet {
      // Re-pin on enable: the existing order has the current track wherever it happened to land,
      // so without this, turning shuffle on near the end of the queue ends it right away.
      guard shuffleEnabled, shuffleEnabled != oldValue else { return }
      shuffleOrder.reshuffle(keepingFirst: currentIndex)
    }
  }

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

  /// Whether `next()` would move to a distinct track — drives remote/CarPlay
  /// next-button enablement. Shares `nextIndex` with `next()` so the button
  /// state and the actual navigation can't diverge (shuffle/repeat-wrap aware).
  var canNext: Bool { nextIndex != nil }

  /// Whether `previous()` would move to a distinct track. Symmetric to `canNext`.
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

  /// The index `next()` will move to — a distinct track in playback order, or
  /// nil when there's nowhere to go (empty/single-track queue, or a real end
  /// with no wrap). Single source of truth shared by `next()` and `canNext`, so
  /// the button state and the actual navigation can't disagree. Shuffle wraps
  /// the order unconditionally (like Media3); sequential wraps only on repeat-all.
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

  /// The index `previous()` will move to. Symmetric to `nextIndex`.
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

  /// Step to the next track in the queue.
  func next() -> QueueNavigationResult {
    guard currentTrack != nil, !tracks.isEmpty else { return .noChange }
    if let nextIndex {
      currentIndex = nextIndex
      return .trackChanged
    }
    // No distinct next: single-track + repeat-all replays; otherwise a real end.
    return tracks.count == 1 && repeatMode == .queue ? .sameTrackReplay : .noChange
  }

  /// Step to the previous track in the queue.
  func previous() -> QueueNavigationResult {
    guard currentTrack != nil, !tracks.isEmpty else { return .noChange }
    if let previousIndex {
      currentIndex = previousIndex
      return .trackChanged
    }
    return tracks.count == 1 && repeatMode == .queue ? .sameTrackReplay : .noChange
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

  // MARK: - Mutations

  /// Replace the entire queue with new tracks. The current track always changes.
  @discardableResult
  func setQueue(_ newTracks: [Track], initialIndex: Int = 0, sourcePath: String? = nil) -> QueueChange {
    let clampedIndex = max(0, min(initialIndex, newTracks.count - 1))
    currentIndex = clampedIndex
    queueSourcePath = sourcePath
    tracks = newTracks
    shuffleOrder = ShuffleOrder(length: newTracks.count, firstIndex: clampedIndex)
    return .tracksAndCurrent
  }

  /// Add tracks to the end of the queue. The current track changes only when the queue was empty.
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

  /// Add tracks at a specific index in the queue. The current track changes only when the queue
  /// was empty.
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
    // Correct index when tracks were inserted in front of it:
    if currentIndex >= 0, currentIndex >= index {
      currentIndex += newTracks.count
    }
    tracks.insert(contentsOf: newTracks, at: index)
    if wasEmpty { return adoptInitialCurrent(0) }
    shuffleOrder.insert(at: index, count: newTracks.count)
    return .tracksOnly
  }

  /// The queue was empty: `index` becomes the current track AND leads the
  /// shuffle order. One operation because the two must not be separable —
  /// forgetting the second half is the bug this exists to prevent.
  ///
  /// `insert(at:count:)` drops new indices at *random* shuffle positions, so
  /// without the pin the starting track can land last in the order, which reads
  /// as `isLastInPlaybackOrder`: the queue "ends" (and fires
  /// `playerDidEndQueue`) the moment the first track finishes. Pins
  /// unconditionally, like `setQueue` — the order has to be sane whether or not
  /// shuffle is on right now, since `shuffleEnabled`'s didSet only re-pins on
  /// the off→on edge.
  ///
  /// Returns the change, so callers can `return adoptInitialCurrent(…)`.
  private func adoptInitialCurrent(_ index: Int) -> QueueChange {
    currentIndex = max(0, min(index, tracks.count - 1))
    shuffleOrder = ShuffleOrder(length: tracks.count, firstIndex: currentIndex)
    return .tracksAndCurrent
  }

  /// Remove a track from the queue. The current track changes when it is the one removed.
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

  /// Move a track in the queue from one position to another. The current track never changes:
  /// the pointer follows the playing track.
  @discardableResult
  func move(fromIndex: Int, toIndex: Int) throws -> QueueChange {
    try throwIfQueueEmpty()
    try throwIfIndexInvalid(index: fromIndex, name: "fromIndex")
    try throwIfIndexInvalid(index: toIndex, name: "toIndex", max: Int.max)
    let track = tracks.remove(at: fromIndex)
    let insertion = min(tracks.count, toIndex)
    tracks.insert(track, at: insertion)
    // Carry the moved track's shuffle position across the move. `insert(at:count:)`
    // would drop it at a RANDOM position instead — reordering the queue would then
    // silently reorder shuffled playback, and moving the playing track could land it
    // last in the order, ending the queue at the next track boundary with tracks
    // still unplayed (the failure `adoptInitialCurrent` guards against elsewhere).
    // `remove` shifts every later position down by one, so re-inserting at the
    // captured position restores the exact prior order.
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

  /// Remove all tracks and reset state. Does nothing when there is no current track.
  @discardableResult
  func clear() -> QueueChange {
    guard currentIndex != -1 else { return .none }
    currentIndex = -1
    tracks.removeAll()
    shuffleOrder.clear()
    queueSourcePath = nil
    return .tracksAndCurrent
  }

  /// Replace the track at a specific index; which index is current does not change.
  /// Throws like every other index-taking mutation here, rather than trapping.
  @discardableResult
  func replace(_ index: Int, _ track: Track) throws -> QueueChange {
    try throwIfIndexInvalid(index: index)
    tracks[index] = track
    return .tracksOnly
  }

  /// Remove all upcoming tracks (those after currentIndex).
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
