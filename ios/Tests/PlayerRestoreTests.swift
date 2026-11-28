@testable import AudioBrowserTestable
import Foundation
import Testing

@MainActor
private func station(_ id: String) -> Track {
  Track(id: id, src: "https://example.com/\(id).mp3", title: id, live: true)
}

@MainActor
private func session(
  _ track: Track,
  queue: [Track]? = nil,
  index: Int? = nil,
) -> PersistedPlaybackState {
  PersistedPlaybackState(
    track: JsonTrack(from: track), positionMs: nil, repeatMode: "queue", shuffleEnabled: false, playbackSpeed: 1,
    queue: queue?.map(JsonTrack.init(from:)), queueIndex: index,
  )
}

@MainActor
private func pausedPlayer() -> (player: Player, engine: MockPlayerEngine, clock: ManualSleepTimerScheduler) {
  let clock = ManualSleepTimerScheduler()
  let engine = MockPlayerEngine()
  let player = makePlayer(engine: engine, sleepTimerScheduler: clock)
  player.setPlayWhenReady(false)
  return (player, engine, clock)
}

@Suite("Player - restore")
struct PlayerRestoreTests {
  @Test @MainActor
  func restoresTheStoredQueueAtItsStation() {
    let (player, _, _) = pausedPlayer()
    let queue = [station("a"), station("b"), station("c")]

    player.restore(session(queue[1], queue: queue, index: 1), queue: nil, playWhenReady: nil)

    #expect(player.tracks.map(\.id) == ["a", "b", "c"])
    #expect(player.currentIndex == 1)
    #expect(!player.playWhenReady)
  }

  @Test @MainActor
  func aPausedRestoreOpensNoStreamAndLeavesTheAudioSessionInactive() {
    let (player, engine, clock) = pausedPlayer()
    let queue = [station("a"), station("b")]

    player.restore(session(queue[0], queue: queue, index: 0), queue: nil, playWhenReady: nil)
    clock.advance(to: Player.idleLiveParkDelaySeconds + 1)

    #expect(engine.resolveAndLoadCalls.isEmpty)
    #expect(engine.activateCount == 0)
    #expect(!player.playingState.playing && !player.playingState.buffering)
  }

  @Test @MainActor
  func aPausedRestoreOfAnOnDemandTrackLoadsNothingUntilPlay() {
    let (player, engine, _) = pausedPlayer()
    let episode = Track(id: "e", src: "https://example.com/e.mp3", title: "e")
    let saved = PersistedPlaybackState(
      track: JsonTrack(from: episode), positionMs: 30000, repeatMode: "off", shuffleEnabled: false,
      playbackSpeed: 1, queue: [JsonTrack(from: episode)], queueIndex: 0,
    )

    player.restore(saved, queue: nil, playWhenReady: nil)

    #expect(engine.resolveAndLoadCalls.isEmpty)
    #expect(player.state == .paused)
    #expect(player.currentTime == 30)

    player.play()

    #expect(engine.resolveAndLoadCalls == ["https://example.com/e.mp3"])
  }

  @Test @MainActor
  func playAfterARestoreConnectsTheStation() {
    let (player, engine, clock) = pausedPlayer()
    player.restore(session(station("a"), queue: [station("a")], index: 0), queue: nil, playWhenReady: nil)
    clock.advance(to: Player.idleLiveParkDelaySeconds + 1)

    player.play()

    #expect(engine.resolveAndLoadCalls == ["https://example.com/a.mp3"])
  }

  @Test @MainActor
  func aPlaySentWhileTheRestoreRanPlaysTheRestoredStation() {
    let (player, engine, _) = pausedPlayer()
    player.play()

    player.restore(session(station("a"), queue: [station("a")], index: 0), queue: nil, playWhenReady: nil)

    #expect(player.playWhenReady)
    #expect(engine.resolveAndLoadCalls == ["https://example.com/a.mp3"])
  }

  @Test @MainActor
  func anExpandedQueueWinsOverTheStoredOne() {
    let (player, _, _) = pausedPlayer()
    let expanded = [station("x"), station("b"), station("y")]

    player.restore(
      session(station("b"), queue: [station("b")], index: 0),
      queue: (expanded, 1, "/page"),
      playWhenReady: nil,
    )

    #expect(player.tracks.map(\.id) == ["x", "b", "y"])
    #expect(player.currentIndex == 1)
    #expect(player.queueSourcePath == "/page")
  }

  @Test @MainActor
  func aStoredQueueWhoseIndexIsNotTheStationRestoresTheStationAlone() {
    let (player, _, _) = pausedPlayer()

    player.restore(
      session(station("b"), queue: [station("a"), station("c")], index: 1), queue: nil, playWhenReady: nil,
    )

    #expect(player.tracks.map(\.id) == ["b"])
  }

  @Test @MainActor
  func aSessionSavedWithoutAQueueRestoresTheStationAlone() {
    let (player, _, _) = pausedPlayer()

    player.restore(session(station("b")), queue: nil, playWhenReady: nil)

    #expect(player.tracks.map(\.id) == ["b"])
    #expect(player.repeatMode == .queue)
  }
}

@Suite("Player persistence")
struct PlayerPersistenceTests {
  @Test @MainActor
  func anEditBeforeThePausedTrackKeepsItsStoredQueueAtItsNewIndex() throws {
    let suite = "PlayerPersistenceTests.edit"
    let defaults = try #require(UserDefaults(suiteName: suite))
    defaults.removePersistentDomain(forName: suite)
    let engine = MockPlayerEngine()
    let player = Player(engine: engine, audioSession: engine, persistenceDefaults: defaults)
    try player.setQueue([station("a"), station("b"), station("c"), station("d")], initialIndex: 3)

    try player.remove(0)

    let stored = Player(engine: engine, audioSession: engine, persistenceDefaults: defaults)
      .persistedSession()?.storedQueue
    #expect(stored?.tracks.map(\.id) == ["b", "c", "d"])
    #expect(stored?.selectedIndex == 2)
  }
}

@Suite("PlaybackRestore")
struct PlaybackRestoreTests {
  @MainActor
  private func run(
    _ player: Player,
    _ saved: PersistedPlaybackState,
    isCurrent: Bool = true,
    since version: Int? = nil,
    repeatMode: RepeatMode? = nil,
    expand: @escaping @MainActor (String, Track, [Track]) async -> RestoreExpansion = { _, _, _ in .unavailable },
    cleared: @escaping @MainActor () -> Bool = { false },
  ) async {
    await PlaybackRestore.run(
      player, session: saved, since: version ?? player.queueVersion, repeatMode: repeatMode, expand: expand,
      isCurrent: { isCurrent }, cleared: cleared,
    )
  }

  @Test @MainActor
  func aClearWhileThePageLoadsRestoresNothingAndStoresNothing() async throws {
    let suite = "PlaybackRestoreTests.clear"
    let defaults = try #require(UserDefaults(suiteName: suite))
    defaults.removePersistentDomain(forName: suite)
    let engine = MockPlayerEngine()
    let player = Player(engine: engine, audioSession: engine, persistenceDefaults: defaults)
    var saved = station("b")
    saved.path = "/page?__trackId=b"
    var cleared = false

    await run(
      player, session(saved, queue: [station("a"), saved], index: 1),
      expand: { _, _, _ in
        PlaybackPersistence.clear(defaults: defaults)
        cleared = true
        return .unavailable
      },
      cleared: { cleared },
    )

    #expect(player.tracks.isEmpty)
    #expect(PlaybackPersistence(defaults: defaults) { nil }.load() == nil)
    #expect(defaults.data(forKey: "playbackQueue.v1") == nil)
  }

  @Test @MainActor
  func aSessionClearedBeforeTheRestoreRunsIsNeverPreviewed() async {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine)
    var saved = station("b")
    saved.path = "/page?__trackId=b"

    await run(player, session(saved, queue: [saved], index: 0), cleared: { true })

    #expect(engine.loadNowPlayingMetadataCalls.isEmpty)
    #expect(player.tracks.isEmpty)
  }

  @Test @MainActor
  func aClearBumpsTheGenerationARestoreChecks() {
    let before = PlaybackPersistence.clearGeneration
    PlaybackPersistence.clear(defaults: UserDefaults(suiteName: "PlaybackRestoreTests.generation")!)
    #expect(PlaybackPersistence.clearGeneration > before)
  }

  @Test @MainActor
  func aRepeatModeTheSetupAskedForWinsOverTheStoredOne() async {
    let player = makePlayer()

    await run(player, session(station("a"), queue: [station("a")], index: 0), repeatMode: .track)

    #expect(player.repeatMode == .track)
  }

  @Test @MainActor
  func withoutARepeatModeFromSetupTheStoredOneIsRestored() async {
    let player = makePlayer()
    player.repeatMode = .off

    await run(player, session(station("a"), queue: [station("a")], index: 0))

    #expect(player.repeatMode == .queue)
  }

  @Test @MainActor
  func aQueueSetAndClearedSinceSetupIsLeftCleared() async throws {
    let player = makePlayer()
    let atSetup = player.queueVersion
    try player.setQueue([station("picked")])
    player.clear()

    await run(player, session(station("a"), queue: [station("a")], index: 0), since: atSetup)

    #expect(player.tracks.isEmpty)
  }

  @Test @MainActor
  func aSetupWithASessionToRestoreStartsPausedThoughItAskedToPlay() {
    #expect(PlaybackRestore.setupIntent(requested: true, session: session(station("a"))) == false)
    #expect(PlaybackRestore.setupIntent(requested: true, session: nil) == true)
    #expect(PlaybackRestore.setupIntent(requested: nil, session: nil) == nil)
  }

  @Test @MainActor
  func aPlaySentBeforeTheRestoreRunsPlaysTheRestoredStation() async {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine)
    player.play()

    await run(player, session(station("a"), queue: [station("a"), station("b")], index: 0))

    #expect(player.tracks.map(\.id) == ["a", "b"])
    #expect(player.playWhenReady)
    #expect(engine.resolveAndLoadCalls == ["https://example.com/a.mp3"])
  }

  @Test @MainActor
  func aContextualPathIsExpandedAfresh() async {
    let player = makePlayer()
    var saved = Track(id: "b", src: "https://example.com/b.mp3", live: true)
    saved.path = "/page?__trackId=b"
    var asked: String?

    await run(player, session(saved, queue: [saved], index: 0)) { path, _, _ in
      asked = path
      return .queue(tracks: [station("a"), station("b")], selectedIndex: 1, sourcePath: "/page")
    }

    #expect(asked == "/page?__trackId=b")
    #expect(player.tracks.map(\.id) == ["a", "b"])
    #expect(player.currentIndex == 1)
  }

  @Test @MainActor
  func aFailedExpansionRestoresTheStoredQueue() async {
    let player = makePlayer()
    var saved = station("b")
    saved.path = "/page?__trackId=b"

    await run(player, session(saved, queue: [station("a"), saved], index: 1))

    #expect(player.tracks.map(\.id) == ["a", "b"])
    #expect(player.currentIndex == 1)
  }

  @Test @MainActor
  func aStationItsPageRefusesIsNotRestoredAndLeavesNothingOnTheNowPlayingSurface() async {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine)
    var saved = station("b")
    saved.path = "/page?__trackId=b"

    await run(player, session(saved, queue: [station("a"), saved], index: 1)) { _, _, _ in
      #expect(engine.loadNowPlayingMetadataCalls.map(\.id) == ["b"])
      engine.clearNowPlayingCallCount = 0
      return .refused
    }

    #expect(player.tracks.isEmpty)
    #expect(engine.clearNowPlayingCallCount == 1)
  }

  @Test @MainActor
  func aPlayDuringTheExpansionPlaysTheRestoredStation() async {
    let engine = MockPlayerEngine()
    let player = makePlayer(engine: engine)
    var saved = station("b")
    saved.path = "/page?__trackId=b"

    await run(player, session(saved, queue: [saved], index: 0)) { _, _, _ in
      player.play()
      return .unavailable
    }

    #expect(player.playWhenReady)
    #expect(engine.resolveAndLoadCalls == ["https://example.com/b.mp3"])
  }

  @Test @MainActor
  func aQueueSetDuringTheExpansionIsKept() async throws {
    let player = makePlayer()
    var saved = station("b")
    saved.path = "/page?__trackId=b"

    await run(player, session(saved, queue: [saved], index: 0)) { _, _, _ in
      try? player.setQueue([station("picked")])
      return .queue(tracks: [station("a"), station("b")], selectedIndex: 1, sourcePath: "/page")
    }

    #expect(player.tracks.map(\.id) == ["picked"])
  }

  @Test @MainActor
  func aPlayerThatHasAQueueIsLeftAlone() async throws {
    let player = makePlayer()
    try player.setQueue([station("current")])

    await run(player, session(station("a"), queue: [station("a")], index: 0))

    #expect(player.tracks.map(\.id) == ["current"])
  }

  @Test @MainActor
  func aPlayerReplacedDuringTheExpansionIsLeftAlone() async {
    let player = makePlayer()

    await run(player, session(station("a"), queue: [station("a")], index: 0), isCurrent: false)

    #expect(player.tracks.isEmpty)
  }
}
