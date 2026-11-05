import Testing

@testable import AudioBrowserTestable

// MARK: - Helpers

@MainActor
private func tracks(_ ids: String...) -> [Track] {
  ids.map { Track(id: $0) }
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

  /// The start track leads the shuffle order: left wherever the permutation put it, it could land
  /// last, and auto-advance would end the queue as soon as it plays (#96).
  @Test func shuffle_startTrackLeadsTheOrder() {
    for start in 0 ..< 6 {
      let q = QueueManager()
      q.setQueue(tracks("a", "b", "c", "d", "e", "f"), initialIndex: start)
      q.shuffleEnabled = true

      #expect(q.shuffleOrder.isFirst(start))
      #expect(!q.isLastInPlaybackOrder)
      #expect(q.nextTracks.count == 5)
      #expect(q.previousTracks.isEmpty)
    }
  }

  /// Enabling shuffle mid-queue pins the current track to the head too, or the queue could report
  /// end-of-queue at once.
  @Test func shuffle_enablingMidQueueLeadsFromCurrent() {
    for start in 0 ..< 6 {
      let q = QueueManager()
      q.setQueue(tracks("a", "b", "c", "d", "e", "f"))
      _ = try? q.skipTo(start)

      q.shuffleEnabled = true

      #expect(q.shuffleOrder.isFirst(start))
      #expect(!q.isLastInPlaybackOrder)
      #expect(q.nextTracks.count == 5)
    }
  }

  /// Only the off→on edge pins: a redundant enable, or disabling, must not reshuffle under the
  /// listener.
  @Test func shuffle_redundantEnableKeepsTheOrder() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c", "d", "e", "f"))
    q.shuffleEnabled = true
    let order = q.shuffleOrder.shuffled

    q.shuffleEnabled = true
    #expect(q.shuffleOrder.shuffled == order)

    q.shuffleEnabled = false
    #expect(q.shuffleOrder.shuffled == order)
  }

  /// A move permutes positions, not identities, so the playback order of the tracks stays as it was.
  @Test func shuffle_moveKeepsThePlaybackOrder() {
    for target in 0 ..< 5 {
      let q = QueueManager()
      q.setQueue(tracks("a", "b", "c", "d", "e"))
      q.shuffleEnabled = true
      let before = q.shuffleOrder.shuffled.map { q.tracks[$0].id }

      _ = try? q.move(fromIndex: 0, toIndex: target)

      #expect(q.shuffleOrder.shuffled.map { q.tracks[$0].id } == before)
    }
  }

  /// Moving the playing track must not drop it last in the shuffle order, which would end the queue
  /// at the next track boundary with tracks unplayed.
  @Test func shuffle_movingCurrentTrackDoesNotEndTheQueue() {
    for target in 1 ..< 6 {
      let q = QueueManager()
      q.setQueue(tracks("a", "b", "c", "d", "e", "f"), initialIndex: 0)
      q.shuffleEnabled = true
      #expect(!q.isLastInPlaybackOrder)

      _ = try? q.move(fromIndex: 0, toIndex: target)

      #expect(q.currentTrack?.id == "a")
      #expect(!q.isLastInPlaybackOrder)
      #expect(q.nextTracks.count == 5)
    }
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
    for _ in 0 ..< 2 {
      _ = q.next()
    }
    let result = q.next()
    #expect(result == .trackChanged)
  }

  @Test func multipleTrack_repeatTrack_behavesLikeOff() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 2)
    q.repeatMode = .track
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

    while q.previousTracks.count > 0 {
      _ = q.previous()
    }
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

  @Test func changesTracksAndCurrent() {
    let q = QueueManager()
    #expect(q.setQueue(tracks("a")) == .tracksAndCurrent)
  }

  @Test func emptyArray_setsCurrentIndexToZero() {
    let q = QueueManager()
    q.setQueue([])
    // max(0, min(0, -1)) clamps to 0, unlike the -1 that clear() and remove() leave.
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
  @Test func toEmptyQueue_changesCurrent_setsCurrentIndex() {
    let q = QueueManager()
    let changed = q.add(tracks("a", "b")).currentTrack
    #expect(changed == true)
    #expect(q.currentIndex == 0)
    #expect(q.tracks.count == 2)
  }

  @Test func toEmptyQueue_respectsInitialIndex() {
    let q = QueueManager()
    let changed = q.add(tracks("a", "b", "c"), initialIndex: 2).currentTrack
    #expect(changed == true)
    #expect(q.currentIndex == 2)
  }

  @Test func toNonEmpty_keepsCurrent_currentIndexUnchanged() {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    let changed = q.add(tracks("b", "c")).currentTrack
    #expect(changed == false)
    #expect(q.currentIndex == 0)
    #expect(q.tracks.count == 3)
  }

  @Test func emptyArray_changesNothing() {
    let q = QueueManager()
    #expect(q.add([]) == .none)
  }

  @Test func toEmptyQueue_clampsInitialIndex() {
    let q = QueueManager()
    let changed = q.add(tracks("a", "b"), initialIndex: 99).currentTrack
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

  /// Filling an empty queue pins the start track to the head of the shuffle order, as setQueue does;
  /// shuffleEnabled pins only on its off→on edge, so with shuffle already on nothing else would.
  @Test func toEmptyQueue_withShuffleAlreadyOn_startTrackLeadsTheOrder() {
    for start in 0 ..< 6 {
      let q = QueueManager()
      q.shuffleEnabled = true
      q.add(tracks("a", "b", "c", "d", "e", "f"), initialIndex: start)

      #expect(q.shuffleOrder.isFirst(start))
      #expect(!q.isLastInPlaybackOrder)
      #expect(q.nextTracks.count == 5)
      #expect(q.previousTracks.isEmpty)
    }
  }
}

// MARK: - Mutations: addAt()

@Suite("addAt()")
@MainActor
struct AddAtTests {
  @Test func beforeCurrent_shiftsCurrentIndex() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 1)
    let changed = try q.addAt(tracks("x", "y"), at: 0).currentTrack
    #expect(changed == false)
    #expect(q.currentIndex == 3)
    #expect(q.tracks.count == 5)
  }

  @Test func afterCurrent_currentIndexUnchanged() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 0)
    let changed = try q.addAt(tracks("x"), at: 2).currentTrack
    #expect(changed == false)
    #expect(q.currentIndex == 0)
    #expect(q.tracks.count == 4)
  }

  @Test func toEmptyQueue_changesCurrent() throws {
    let q = QueueManager()
    let changed = try q.addAt(tracks("a"), at: 0).currentTrack
    #expect(changed == true)
    #expect(q.currentIndex == 0)
  }

  /// The same pin as add() into an empty queue.
  @Test func toEmptyQueue_withShuffleAlreadyOn_startTrackLeadsTheOrder() throws {
    let q = QueueManager()
    q.shuffleEnabled = true
    try q.addAt(tracks("a", "b", "c", "d", "e", "f"), at: 0)

    #expect(q.shuffleOrder.isFirst(0))
    #expect(!q.isLastInPlaybackOrder)
    #expect(q.nextTracks.count == 5)
    #expect(q.previousTracks.isEmpty)
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

  @Test func emptyArray_changesNothing() throws {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    #expect(try q.addAt([], at: 0) == .none)
  }

  @Test func singleExistingTrack_insertBeforeCurrent_shifts() throws {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    let changed = try q.addAt(tracks("x"), at: 0).currentTrack
    #expect(changed == false)
    #expect(q.currentIndex == 1)
    #expect(q.currentTrack?.id == "a")
  }

  @Test func atExactCurrentIndex_shiftsCurrentIndex() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 1)
    let changed = try q.addAt(tracks("x"), at: 1).currentTrack
    #expect(changed == false)
    #expect(q.currentIndex == 2)
    #expect(q.currentTrack?.id == "b")
  }

  @Test func atEnd_appendsTrack() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    let changed = try q.addAt(tracks("x"), at: 2).currentTrack
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
  @Test func currentTrack_changesCurrent_wrapsIndex() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 2)
    let changed = try q.remove(2).currentTrack
    #expect(changed == true)
    #expect(q.currentIndex == 0)
    #expect(q.tracks.count == 2)
  }

  @Test func beforeCurrent_adjustsIndex_keepsCurrent() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 2)
    let changed = try q.remove(0).currentTrack
    #expect(changed == false)
    #expect(q.currentIndex == 1)
    #expect(q.tracks.count == 2)
  }

  @Test func afterCurrent_keepsCurrent() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 0)
    let changed = try q.remove(2).currentTrack
    #expect(changed == false)
    #expect(q.currentIndex == 0)
    #expect(q.tracks.count == 2)
  }

  @Test func lastRemaining_currentIndexBecomesMinusOne() throws {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    let changed = try q.remove(0).currentTrack
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
    let changed = try q.remove(1).currentTrack
    #expect(changed == true)
    #expect(q.currentIndex == 1)
    #expect(q.currentTrack?.id == "c")
  }

  @Test func currentAtZero_staysAtZero() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"))
    let changed = try q.remove(0).currentTrack
    #expect(changed == true)
    #expect(q.currentIndex == 0)
    #expect(q.currentTrack?.id == "b")
  }

  @Test func beforeCurrent_verifiesCorrectTrack() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 2)
    try q.remove(0)
    #expect(q.currentTrack?.id == "c")
  }
}

// MARK: - Mutations: move()

@Suite("move()")
@MainActor
struct MoveTests {
  // No move reports a current-track change: the caller reloads on one, which would restart
  // playback on a reorder.

  @Test func currentTrack_pointerFollows() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 0)
    let changed = try q.move(fromIndex: 0, toIndex: 2).currentTrack
    #expect(changed == false)
    #expect(q.currentIndex == 2)
    #expect(q.currentTrack?.id == "a")
  }

  @Test func nonCurrent_keepsCurrent() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 0)
    let changed = try q.move(fromIndex: 1, toIndex: 2).currentTrack
    #expect(changed == false)
    #expect(q.currentIndex == 0)
  }

  @Test func emptyQueue_throws() {
    let q = QueueManager()
    #expect(throws: TrackPlayerError.QueueError.self) {
      try q.move(fromIndex: 0, toIndex: 1)
    }
  }

  @Test func nonCurrent_acrossCurrent_keepsPointerOnSameTrack() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 1)
    let changed = try q.move(fromIndex: 0, toIndex: 2).currentTrack
    #expect(changed == false)
    #expect(q.currentTrack?.id == "b")
    #expect(q.currentIndex == 0)
  }

  @Test func nonCurrent_acrossCurrentBackwards_keepsPointerOnSameTrack() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 1)
    let changed = try q.move(fromIndex: 2, toIndex: 0).currentTrack
    #expect(changed == false)
    #expect(q.currentTrack?.id == "b")
    #expect(q.currentIndex == 2)
  }

  @Test func toIndexBeyondCount_clampsPointerToo() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 0)
    let changed = try q.move(fromIndex: 0, toIndex: 99).currentTrack
    #expect(changed == false)
    #expect(q.tracks[2].id == "a")
    #expect(q.currentIndex == 2)
    #expect(q.currentTrack?.id == "a")
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
  @Test func withTracks_changesCurrent_resetsToMinusOne() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    let changed = q.clear().currentTrack
    #expect(changed == true)
    #expect(q.currentIndex == -1)
    #expect(q.tracks.isEmpty)
    #expect(q.queueSourcePath == nil)
  }

  @Test func alreadyEmpty_changesNothing() {
    let q = QueueManager()
    #expect(q.clear() == .none)
  }

  /// A stale shuffle order over a rebuilt queue navigates to indices that no longer exist, nil-ing
  /// currentTrack mid-session.
  @Test func clear_alsoClearsShuffleOrder() {
    let q = QueueManager()
    q.shuffleEnabled = true
    q.setQueue(tracks("a", "b", "c", "d", "e"))

    q.clear()

    #expect(q.shuffleOrder.shuffled.isEmpty)

    _ = try? q.addAt(tracks("x", "y"), at: 0)
    #expect(q.shuffleOrder.shuffled.count == q.tracks.count)
  }
}

// MARK: - Other: replace, removeUpcomingTracks

@Suite("replace & removeUpcomingTracks")
@MainActor
struct OtherMutationTests {
  @Test func replace_updatesTrackAtIndex() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"))
    try q.replace(1, Track(id: "B"))
    #expect(q.tracks[1].id == "B")
  }

  @Test func removeUpcomingTracks_alsoTrimsShuffleOrder() {
    let q = QueueManager()
    q.shuffleEnabled = true
    q.setQueue(tracks("a", "b", "c", "d", "e"), initialIndex: 1)

    q.removeUpcomingTracks()

    #expect(q.shuffleOrder.shuffled.count == q.tracks.count)
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

  @Test func replace_currentTrack_reflectsInCurrentTrack() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 1)
    try q.replace(1, Track(id: "B"))
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
    let nextFromProp = q.nextTracks.map { $0.id! }
    var nextFromNav: [String] = []
    for _ in 0 ..< nextFromProp.count {
      _ = q.next()
      nextFromNav.append(q.currentTrack!.id!)
    }
    #expect(nextFromProp == nextFromNav)
  }

  @Test func previousTracks_shuffle_matchesNavigation() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c", "d"))
    q.shuffleEnabled = true
    for _ in 0 ..< 3 {
      _ = q.next()
    }
    let prevFromProp = q.previousTracks.map { $0.id! }
    var prevFromNav: [String] = []
    for _ in 0 ..< prevFromProp.count {
      _ = q.previous()
      prevFromNav.insert(q.currentTrack!.id!, at: 0)
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
    while !q.nextTracks.isEmpty {
      _ = q.next()
    }
    #expect(q.isLastInPlaybackOrder == true)
    _ = q.next()
    #expect(q.isLastInPlaybackOrder == false)
  }

  @Test func isLastInPlaybackOrder_emptyQueue() {
    let q = QueueManager()
    // currentIndex -1 equals tracks.count - 1.
    #expect(q.isLastInPlaybackOrder == true)
  }
}

// MARK: - QueueChange

@Suite("QueueChange")
@MainActor
struct QueueChangeTests {
  @Test func setQueue_changesTracksAndCurrent() {
    #expect(QueueManager().setQueue(tracks("a", "b")) == .tracksAndCurrent)
  }

  @Test func addToEmpty_changesTracksAndCurrent() {
    #expect(QueueManager().add(tracks("a")) == .tracksAndCurrent)
  }

  @Test func addToNonEmpty_changesTracksOnly() {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    #expect(q.add(tracks("b")) == .tracksOnly)
  }

  @Test func addAt_changesTracksOnly() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    #expect(try q.addAt(tracks("x"), at: 1) == .tracksOnly)
    #expect(q.tracks.count == 3)
  }

  @Test func removeCurrent_changesTracksAndCurrent() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    #expect(try q.remove(0) == .tracksAndCurrent)
  }

  @Test func removeOther_changesTracksOnly() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    #expect(try q.remove(1) == .tracksOnly)
  }

  @Test func clear_changesTracksAndCurrent() {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    #expect(q.clear() == .tracksAndCurrent)
    #expect(q.tracks.isEmpty)
  }

  @Test func moveCurrent_changesTracksOnly() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"))
    #expect(try q.move(fromIndex: 0, toIndex: 2) == .tracksOnly)
  }

  @Test func replaceCurrent_changesTracksOnly() throws {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"))
    #expect(try q.replace(0, Track(id: "A")) == .tracksOnly)
  }

  @Test func removeUpcomingTracks_changesTracksOnly() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 0)
    #expect(q.removeUpcomingTracks() == .tracksOnly)
    #expect(q.tracks.count == 1)
  }

  @Test func removeUpcomingTracks_atEnd_changesNothing() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b"), initialIndex: 1)
    #expect(q.removeUpcomingTracks() == .none)
  }
}

// MARK: - canNext / canPrevious

@Suite("canNext / canPrevious")
@MainActor
struct SkipAvailabilityTests {
  @Test func emptyQueue_bothFalse() {
    let q = QueueManager()
    #expect(q.canNext == false)
    #expect(q.canPrevious == false)
  }

  @Test func singleTrack_repeatOff_bothFalse() {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    #expect(q.canNext == false)
    #expect(q.canPrevious == false)
  }

  @Test func singleTrack_repeatQueue_bothFalse() {
    let q = QueueManager()
    q.setQueue(tracks("a"))
    q.repeatMode = .queue
    // next() would replay the same track, which is not a move to a distinct one.
    #expect(q.canNext == false)
    #expect(q.canPrevious == false)
  }

  @Test func firstOfMany_nextOnly() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 0)
    #expect(q.canNext == true)
    #expect(q.canPrevious == false)
  }

  @Test func middleOfMany_both() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 1)
    #expect(q.canNext == true)
    #expect(q.canPrevious == true)
  }

  @Test func lastOfMany_repeatOff_previousOnly() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 2)
    #expect(q.canNext == false)
    #expect(q.canPrevious == true)
  }

  @Test func lastOfMany_repeatQueue_bothTrue() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 2)
    q.repeatMode = .queue
    #expect(q.canNext == true)
    #expect(q.canPrevious == true)
  }

  @Test func firstOfMany_repeatQueue_bothTrue() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c"), initialIndex: 0)
    q.repeatMode = .queue
    #expect(q.canNext == true)
    #expect(q.canPrevious == true)
  }

  // Shuffle wraps even with repeat off, so canNext and canPrevious stay true at the order's ends.
  @Test func shuffleEnd_repeatOff_canNextTrue() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c", "d"))
    q.shuffleEnabled = true
    q.repeatMode = .off
    while !q.isLastInPlaybackOrder {
      _ = q.next()
    }
    #expect(q.canNext == true)
  }

  @Test func shuffleStart_repeatOff_canPreviousTrue() {
    let q = QueueManager()
    q.setQueue(tracks("a", "b", "c", "d"))
    q.shuffleEnabled = true
    q.repeatMode = .off
    while !q.previousTracks.isEmpty {
      _ = q.previous()
    }
    #expect(q.canPrevious == true)
  }
}
