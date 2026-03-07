import Testing

@testable import AudioBrowserTestable

// MARK: - Helpers

@MainActor
private func tracks(_ ids: String...) -> [Track] {
  ids.map { Track(id: $0) }
}

/// A delegate that records every `queueDidChangeTracks` callback.
@MainActor
private final class DelegateSpy: QueueManagerDelegate {
  var calls: [[Track]] = []

  func queueDidChangeTracks(_ tracks: [Track]) {
    calls.append(tracks)
  }
}

// MARK: - Navigation: next()

@Suite("next()")
@MainActor
struct NextTests {
  @Test func emptyQueue_returnsNoChange() {
    let q = QueueManager()
    #expect(q.next() == .noChange)
  }

  @Test func singleTrack_repeatOff_returnsNoChange() {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    q.repeatMode = .off
    #expect(q.next() == .noChange)
  }

  @Test func singleTrack_repeatTrack_returnsNoChange() {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    q.repeatMode = .track
    #expect(q.next() == .noChange)
  }

  @Test func singleTrack_repeatQueue_returnsSameTrackReplay() {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    q.repeatMode = .queue
    #expect(q.next() == .sameTrackReplay)
  }

  @Test func sequential_advancesIndex() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"))
    #expect(q.next() == .trackChanged)
    #expect(q.currentIndex == 1)
    #expect(q.currentTrack?.id == "b")
  }

  @Test func sequential_atEnd_repeatOff_returnsNoChange() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"), initialIndex: 1)
    q.repeatMode = .off
    #expect(q.next() == .noChange)
    #expect(q.currentIndex == 1)
  }

  @Test func sequential_atEnd_repeatQueue_wrapsToZero() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"), initialIndex: 1)
    q.repeatMode = .queue
    #expect(q.next() == .trackChanged)
    #expect(q.currentIndex == 0)
  }

  @Test func shuffle_visitsAllTracks() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c", "d"))
    q.shuffleEnabled = true
    var visited = Set<Int>()
    visited.insert(q.currentIndex)
    for _ in 0 ..< 3 {
      let result = q.next()
      #expect(result == .trackChanged)
      visited.insert(q.currentIndex)
    }
    #expect(visited.count == 4)
  }

  @Test func shuffle_atEnd_wrapsEvenWithRepeatOff() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"))
    q.shuffleEnabled = true
    q.repeatMode = .off
    // Navigate to the end of the shuffle order
    for _ in 0 ..< 2 {
      _ = q.next()
    }
    // One more should wrap
    let result = q.next()
    #expect(result == .trackChanged)
  }

  @Test func multipleTrack_repeatTrack_behavesLikeOff() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 2)
    q.repeatMode = .track
    // .track only triggers sameTrackReplay for single-track queues;
    // with multiple tracks it behaves like .off at the boundary.
    #expect(q.next() == .noChange)
  }
}

// MARK: - Navigation: previous()

@Suite("previous()")
@MainActor
struct PreviousTests {
  @Test func atBeginning_repeatOff_returnsNoChange() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    q.repeatMode = .off
    #expect(q.previous() == .noChange)
  }

  @Test func atBeginning_repeatQueue_wrapsToLast() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"))
    q.repeatMode = .queue
    #expect(q.previous() == .trackChanged)
    #expect(q.currentIndex == 2)
  }

  @Test func sequential_decrementsIndex() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 2)
    #expect(q.previous() == .trackChanged)
    #expect(q.currentIndex == 1)
  }

  @Test func shuffle_atBeginning_wrapsEvenWithRepeatOff() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"))
    q.shuffleEnabled = true
    q.repeatMode = .off
    // Navigate to the beginning of the shuffle order
    // Find the first index in the shuffle order
    // Move to the first shuffle position by calling previous until we can't
    // Actually, the queue starts at index 0. The shuffle position of index 0
    // may or may not be position 0 in the shuffle order.
    // Navigate to the first position in shuffle order by calling previous
    // until we hit the boundary, then one more should wrap.

    // Navigate backwards until we reach the first in shuffle order
    while q.previousTracks.count > 0 {
      _ = q.previous()
    }
    // Now we're at the first in shuffle order — one more should wrap
    let result = q.previous()
    #expect(result == .trackChanged)
  }

  @Test func singleTrack_repeatQueue_returnsSameTrackReplay() {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    q.repeatMode = .queue
    #expect(q.previous() == .sameTrackReplay)
  }

  @Test func emptyQueue_returnsNoChange() {
    let q = QueueManager()
    #expect(q.previous() == .noChange)
  }

  @Test func multipleTrack_repeatTrack_behavesLikeOff() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"))
    q.repeatMode = .track
    // .track only triggers sameTrackReplay for single-track queues;
    // with multiple tracks it behaves like .off at the boundary.
    #expect(q.previous() == .noChange)
  }
}

// MARK: - Navigation: skipTo()

@Suite("skipTo()")
@MainActor
struct SkipToTests {
  @Test func sameIndex_returnsNoChange() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    #expect(try q.skipTo(0) == .noChange)
  }

  @Test func differentValidIndex_returnsTrackChanged() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"))
    #expect(try q.skipTo(2) == .trackChanged)
    #expect(q.currentIndex == 2)
    #expect(q.currentTrack?.id == "c")
  }

  @Test func emptyQueue_throws() {
    let q = QueueManager()
    #expect(throws: TrackPlayerError.QueueError.self) {
      try q.skipTo(0)
    }
  }

  @Test func outOfRange_throws() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    #expect(throws: TrackPlayerError.QueueError.self) {
      try q.skipTo(5)
    }
  }

  @Test func negativeIndex_throws() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    #expect(throws: TrackPlayerError.QueueError.self) {
      try q.skipTo(-1)
    }
  }
}

// MARK: - Mutations: setQueue()

@Suite("setQueue()")
@MainActor
struct SetQueueTests {
  @Test func setsTracksAndCurrentIndex() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 1)
    #expect(q.tracks.count == 3)
    #expect(q.currentIndex == 1)
    #expect(q.currentTrack?.id == "b")
  }

  @Test func clampsOutOfRangeInitialIndex() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"), initialIndex: 99)
    #expect(q.currentIndex == 1)
  }

  @Test func clampsNegativeInitialIndex() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"), initialIndex: -5)
    #expect(q.currentIndex == 0)
  }

  @Test func setsQueueSourcePath() {
    let q = QueueManager()
    q.setQueue(tracks("a"), sourcePath: "/albums/123")
    #expect(q.queueSourcePath == "/albums/123")
  }

  @Test func returnsTrue() {
    let q = QueueManager()
    #expect(q.setQueue(tracks("a")) == true)
  }

  @Test func emptyArray_setsCurrentIndexToZero() {
    let q = QueueManager()
    q.setQueue([])
    // min(0, -1) = -1, max(0, -1) = 0 → currentIndex is 0 despite empty tracks
    #expect(q.currentIndex == 0)
    #expect(q.currentTrack == nil)
  }

  @Test func replacesExistingQueue() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"), sourcePath: "/old")
    q.setQueue(tracks("x", "y", "z"), sourcePath: "/new")
    #expect(q.tracks.count == 3)
    #expect(q.tracks[0].id == "x")
    #expect(q.queueSourcePath == "/new")
  }

  @Test func clearsSourcePathWhenNotProvided() {
    let q = QueueManager()
    q.setQueue(tracks("a"), sourcePath: "/old")
    q.setQueue(tracks("b"))
    #expect(q.queueSourcePath == nil)
  }
}

// MARK: - Mutations: add()

@Suite("add()")
@MainActor
struct AddTests {
  @Test func toEmptyQueue_returnsTrue_setsCurrentIndex() {
    let q = QueueManager()
    let changed = q.add(tracks("a", "b"))
    #expect(changed == true)
    #expect(q.currentIndex == 0)
    #expect(q.tracks.count == 2)
  }

  @Test func toEmptyQueue_respectsInitialIndex() {
    let q = QueueManager()
    let changed = q.add(tracks("a", "b", "c"), initialIndex: 2)
    #expect(changed == true)
    #expect(q.currentIndex == 2)
  }

  @Test func toNonEmpty_returnsFalse_currentIndexUnchanged() {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    let changed = q.add(tracks("b", "c"))
    #expect(changed == false)
    #expect(q.currentIndex == 0)
    #expect(q.tracks.count == 3)
  }

  @Test func emptyArray_returnsFalse() {
    let q = QueueManager()
    #expect(q.add([]) == false)
  }

  @Test func toEmptyQueue_clampsInitialIndex() {
    let q = QueueManager()
    let changed = q.add(tracks("a", "b"), initialIndex: 99)
    #expect(changed == true)
    #expect(q.currentIndex == 1)
  }

  @Test func toNonEmpty_appendsInOrder() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    q.add(tracks("c", "d"))
    #expect(q.tracks.count == 4)
    #expect(q.tracks[2].id == "c")
    #expect(q.tracks[3].id == "d")
  }
}

// MARK: - Mutations: addAt()

@Suite("addAt()")
@MainActor
struct AddAtTests {
  @Test func beforeCurrent_shiftsCurrentIndex() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 1)
    let changed = try q.addAt(tracks("x", "y"), at: 0)
    #expect(changed == false)
    #expect(q.currentIndex == 3) // was 1, shifted by 2
    #expect(q.tracks.count == 5)
  }

  @Test func afterCurrent_currentIndexUnchanged() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 0)
    let changed = try q.addAt(tracks("x"), at: 2)
    #expect(changed == false)
    #expect(q.currentIndex == 0)
    #expect(q.tracks.count == 4)
  }

  @Test func toEmptyQueue_returnsTrue() throws {
    let q = QueueManager()
    let changed = try q.addAt(tracks("a"), at: 0)
    #expect(changed == true)
    #expect(q.currentIndex == 0)
  }

  @Test func invalidIndex_throws() {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    #expect(throws: TrackPlayerError.QueueError.self) {
      try q.addAt(tracks("x"), at: 5)
    }
  }

  @Test func negativeIndex_throws() {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    #expect(throws: TrackPlayerError.QueueError.self) {
      try q.addAt(tracks("x"), at: -1)
    }
  }

  @Test func emptyArray_returnsFalse() throws {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    #expect(try q.addAt([], at: 0) == false)
  }

  @Test func singleExistingTrack_insertBeforeCurrent_doesNotShift() throws {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    // guard `tracks.count > 1` is false (count is 1), so currentIndex is NOT shifted.
    // The original track moves to index 1 but currentIndex stays 0.
    let changed = try q.addAt(tracks("x"), at: 0)
    #expect(changed == false)
    #expect(q.currentIndex == 0)
    // currentTrack is now "x" (the inserted track), not "a"
    #expect(q.currentTrack?.id == "x")
  }

  @Test func atExactCurrentIndex_shiftsCurrentIndex() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 1)
    // currentIndex >= index (1 >= 1) triggers the shift
    let changed = try q.addAt(tracks("x"), at: 1)
    #expect(changed == false)
    #expect(q.currentIndex == 2)
    // "b" should still be the current track
    #expect(q.currentTrack?.id == "b")
  }

  @Test func atEnd_appendsTrack() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    // index == tracks.count is the valid append boundary
    let changed = try q.addAt(tracks("x"), at: 2)
    #expect(changed == false)
    #expect(q.tracks.count == 3)
    #expect(q.tracks[2].id == "x")
    #expect(q.currentIndex == 0)
  }
}

// MARK: - Mutations: remove()

@Suite("remove()")
@MainActor
struct RemoveTests {
  @Test func currentTrack_returnsTrue_wrapsIndex() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 2)
    let changed = try q.remove(2)
    #expect(changed == true)
    // After removing index 2, tracks has 2 items, currentIndex wraps: 2 % 2 = 0
    #expect(q.currentIndex == 0)
    #expect(q.tracks.count == 2)
  }

  @Test func beforeCurrent_adjustsIndex_returnsFalse() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 2)
    let changed = try q.remove(0)
    #expect(changed == false)
    #expect(q.currentIndex == 1) // was 2, decremented
    #expect(q.tracks.count == 2)
  }

  @Test func afterCurrent_returnsFalse() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 0)
    let changed = try q.remove(2)
    #expect(changed == false)
    #expect(q.currentIndex == 0)
    #expect(q.tracks.count == 2)
  }

  @Test func lastRemaining_currentIndexBecomesMinusOne() throws {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    let changed = try q.remove(0)
    #expect(changed == true)
    #expect(q.currentIndex == -1)
    #expect(q.tracks.isEmpty)
  }

  @Test func emptyQueue_throws() {
    let q = QueueManager()
    #expect(throws: TrackPlayerError.QueueError.self) {
      try q.remove(0)
    }
  }

  @Test func invalidIndex_throws() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    #expect(throws: TrackPlayerError.QueueError.self) {
      try q.remove(5)
    }
  }

  @Test func negativeIndex_throws() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    #expect(throws: TrackPlayerError.QueueError.self) {
      try q.remove(-1)
    }
  }

  @Test func currentAtMiddle_wrapsCorrectly() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 1)
    let changed = try q.remove(1)
    #expect(changed == true)
    // 1 % 2 = 1, pointing to what was "c"
    #expect(q.currentIndex == 1)
    #expect(q.currentTrack?.id == "c")
  }

  @Test func currentAtZero_staysAtZero() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"))
    let changed = try q.remove(0)
    #expect(changed == true)
    // 0 % 2 = 0
    #expect(q.currentIndex == 0)
    #expect(q.currentTrack?.id == "b")
  }

  @Test func beforeCurrent_verifiesCorrectTrack() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 2)
    try q.remove(0)
    // currentIndex was 2, decremented to 1; track "c" should still be current
    #expect(q.currentTrack?.id == "c")
  }
}

// MARK: - Mutations: move()

@Suite("move()")
@MainActor
struct MoveTests {
  @Test func currentTrack_returnsTrue_updatesIndex() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 0)
    let changed = try q.move(fromIndex: 0, toIndex: 2)
    #expect(changed == true)
    #expect(q.currentIndex == 2)
    #expect(q.tracks[2].id == "a")
  }

  @Test func nonCurrent_returnsFalse() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 0)
    let changed = try q.move(fromIndex: 1, toIndex: 2)
    #expect(changed == false)
    #expect(q.currentIndex == 0)
  }

  @Test func emptyQueue_throws() {
    let q = QueueManager()
    #expect(throws: TrackPlayerError.QueueError.self) {
      try q.move(fromIndex: 0, toIndex: 1)
    }
  }

  @Test func nonCurrent_acrossCurrent_doesNotAdjustIndex() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 1)
    // Move track from before current to after current.
    // currentIndex is NOT adjusted (only adjusted when fromIndex == currentIndex).
    let changed = try q.move(fromIndex: 0, toIndex: 2)
    #expect(changed == false)
    #expect(q.currentIndex == 1)
    // After remove(0): [b, c], insert at min(2,2)=2: [b, c, a]
    // currentIndex 1 now points to "c", not "b"
    #expect(q.currentTrack?.id == "c")
  }

  @Test func toIndexBeyondCount_clampsToEnd() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 0)
    let changed = try q.move(fromIndex: 0, toIndex: 99)
    #expect(changed == true)
    // remove(0): [b, c], insert at min(2, 99)=2: [b, c, a]
    #expect(q.tracks[2].id == "a")
  }

  @Test func invalidFromIndex_throws() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"))
    #expect(throws: TrackPlayerError.QueueError.self) {
      try q.move(fromIndex: 5, toIndex: 0)
    }
  }

  @Test func negativeToIndex_throws() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"))
    #expect(throws: TrackPlayerError.QueueError.self) {
      try q.move(fromIndex: 0, toIndex: -1)
    }
  }
}

// MARK: - Mutations: clear()

@Suite("clear()")
@MainActor
struct ClearTests {
  @Test func withTracks_returnsTrue_resetsToMinusOne() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    let changed = q.clear()
    #expect(changed == true)
    #expect(q.currentIndex == -1)
    #expect(q.tracks.isEmpty)
    #expect(q.queueSourcePath == nil)
  }

  @Test func alreadyEmpty_returnsFalse() {
    let q = QueueManager()
    #expect(q.clear() == false)
  }
}

// MARK: - Other: replace, removeUpcomingTracks

@Suite("replace & removeUpcomingTracks")
@MainActor
struct OtherMutationTests {
  @Test func replace_updatesTrackAtIndex() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"))
    q.replace(1, Track(id: "B"))
    #expect(q.tracks[1].id == "B")
  }

  @Test func removeUpcomingTracks_keepsCurrentAndBefore() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c", "d"), initialIndex: 1)
    q.removeUpcomingTracks()
    #expect(q.tracks.count == 2)
    #expect(q.tracks[0].id == "a")
    #expect(q.tracks[1].id == "b")
  }

  @Test func removeUpcomingTracks_atEnd_noOp() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"), initialIndex: 1)
    q.removeUpcomingTracks()
    #expect(q.tracks.count == 2)
  }

  @Test func removeUpcomingTracks_emptyQueue_noOp() {
    let q = QueueManager()
    q.removeUpcomingTracks()
    #expect(q.tracks.isEmpty)
  }

  @Test func removeUpcomingTracks_atIndexZero_keepsOnlyCurrent() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c", "d"), initialIndex: 0)
    q.removeUpcomingTracks()
    #expect(q.tracks.count == 1)
    #expect(q.tracks[0].id == "a")
  }

  @Test func replace_currentTrack_reflectsInCurrentTrack() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 1)
    q.replace(1, Track(id: "B"))
    #expect(q.currentTrack?.id == "B")
    #expect(q.currentIndex == 1)
  }

  @Test func removeUpcomingTracks_preservesCurrentIndex() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c", "d"), initialIndex: 2)
    q.removeUpcomingTracks()
    #expect(q.currentIndex == 2)
    #expect(q.currentTrack?.id == "c")
  }
}

// MARK: - Computed properties

@Suite("computed properties")
@MainActor
struct ComputedPropertyTests {
  @Test func currentTrack_validIndex() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"), initialIndex: 1)
    #expect(q.currentTrack?.id == "b")
  }

  @Test func currentTrack_noTrack() {
    let q = QueueManager()
    #expect(q.currentTrack == nil)
  }

  @Test func nextTracks_sequential() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c", "d"), initialIndex: 1)
    let next = q.nextTracks
    #expect(next.count == 2)
    #expect(next[0].id == "c")
    #expect(next[1].id == "d")
  }

  @Test func nextTracks_atEnd_isEmpty() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"), initialIndex: 1)
    #expect(q.nextTracks.isEmpty)
  }

  @Test func previousTracks_sequential() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c", "d"), initialIndex: 2)
    let prev = q.previousTracks
    #expect(prev.count == 2)
    #expect(prev[0].id == "a")
    #expect(prev[1].id == "b")
  }

  @Test func previousTracks_atStart_isEmpty() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    #expect(q.previousTracks.isEmpty)
  }

  @Test func isLastInPlaybackOrder_sequential() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"))
    #expect(q.isLastInPlaybackOrder == false)
    q.setQueue(tracks("a", "b", "c"), initialIndex: 2)
    #expect(q.isLastInPlaybackOrder == true)
  }

  @Test func nextTracks_shuffle_matchesNavigation() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c", "d"))
    q.shuffleEnabled = true
    let nextFromProp = q.nextTracks.map(\.id)
    // Navigate forward only as many times as nextTracks reports
    // (nextTracks does not include wrapped tracks)
    var nextFromNav: [String] = []
    for _ in 0 ..< nextFromProp.count {
      _ = q.next()
      nextFromNav.append(q.currentTrack!.id)
    }
    #expect(nextFromProp == nextFromNav)
  }

  @Test func previousTracks_shuffle_matchesNavigation() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c", "d"))
    q.shuffleEnabled = true
    // Navigate to end of shuffle order so there are previous tracks
    for _ in 0 ..< 3 {
      _ = q.next()
    }
    let prevFromProp = q.previousTracks.map(\.id)
    // Navigate backward the same number of times
    var prevFromNav: [String] = []
    for _ in 0 ..< prevFromProp.count {
      _ = q.previous()
      prevFromNav.insert(q.currentTrack!.id, at: 0)
    }
    #expect(prevFromProp == prevFromNav)
  }

  @Test func nextTracks_emptyQueue_isEmpty() {
    let q = QueueManager()
    #expect(q.nextTracks.isEmpty)
  }

  @Test func previousTracks_emptyQueue_isEmpty() {
    let q = QueueManager()
    #expect(q.previousTracks.isEmpty)
  }

  @Test func isLastInPlaybackOrder_shuffle() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"))
    q.shuffleEnabled = true
    // Navigate forward until nextTracks is empty (= last in shuffle order)
    while !q.nextTracks.isEmpty {
      _ = q.next()
    }
    #expect(q.isLastInPlaybackOrder == true)
    // Navigate one more (wraps) — should no longer be last
    _ = q.next()
    #expect(q.isLastInPlaybackOrder == false)
  }

  @Test func isLastInPlaybackOrder_emptyQueue() {
    let q = QueueManager()
    // currentIndex is -1, tracks.count - 1 is -1, so this returns true
    // by the formula `currentIndex == tracks.count - 1`.
    // ShuffleOrder.isLast returns false for index -1.
    #expect(q.isLastInPlaybackOrder == true)
  }
}

// MARK: - Delegate

@Suite("delegate")
@MainActor
struct DelegateTests {
  @Test func queueDidChangeTracks_firesOnSetQueue() {
    let q = QueueManager()
    let spy = DelegateSpy()
    q.delegate = spy
    q.setQueue(tracks("a", "b"))
    #expect(spy.calls.count == 1)
    #expect(spy.calls[0].count == 2)
  }

  @Test func queueDidChangeTracks_firesOnAdd() {
    let q = QueueManager()
    let spy = DelegateSpy()
    q.delegate = spy
    q.add(tracks("a"))
    #expect(spy.calls.count == 1)
  }

  @Test func queueDidChangeTracks_firesOnRemove() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    let spy = DelegateSpy()
    q.delegate = spy
    try q.remove(1)
    #expect(spy.calls.count == 1)
    #expect(spy.calls[0].count == 1)
  }

  @Test func queueDidChangeTracks_firesOnClear() {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    let spy = DelegateSpy()
    q.delegate = spy
    q.clear()
    #expect(spy.calls.count == 1)
    #expect(spy.calls[0].isEmpty)
  }

  @Test func queueDidChangeTracks_firesOnMove() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"))
    let spy = DelegateSpy()
    q.delegate = spy
    try q.move(fromIndex: 0, toIndex: 2)
    #expect(spy.calls.count == 1)
  }

  @Test func queueDidChangeTracks_firesOnReplace() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    let spy = DelegateSpy()
    q.delegate = spy
    q.replace(0, Track(id: "A"))
    #expect(spy.calls.count == 1)
  }

  @Test func queueDidChangeTracks_firesOnRemoveUpcomingTracks() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 0)
    let spy = DelegateSpy()
    q.delegate = spy
    q.removeUpcomingTracks()
    #expect(spy.calls.count == 1)
    #expect(spy.calls[0].count == 1)
  }

  @Test func queueDidChangeTracks_firesOnAddAt() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    let spy = DelegateSpy()
    q.delegate = spy
    try q.addAt(tracks("x"), at: 1)
    #expect(spy.calls.count == 1)
    #expect(spy.calls[0].count == 3)
  }

  @Test func queueDidChangeTracks_doesNotFireOnNavigation() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 1)
    let spy = DelegateSpy()
    q.delegate = spy
    _ = q.next()
    _ = q.previous()
    try q.skipTo(2)
    #expect(spy.calls.isEmpty)
  }
}
