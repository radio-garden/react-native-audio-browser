@testable import AudioBrowserTestable
import Testing

@MainActor
private final class Heard {
  var repeatModes: [RepeatMode] = []
  var intents: [Bool] = []
}

/// The staging, and the player it reads as the current one, which a test swaps as `runSetup` does.
@MainActor
private final class Staged {
  var player: Player?
  let heard: Heard
  private(set) lazy var state = StagedPlayerState(
    currentPlayer: { [unowned self] in player },
    onRepeatModeChanged: { [heard] in heard.repeatModes.append($0) },
    onPlayWhenReadyChanged: { [heard] in heard.intents.append($0) },
  )

  /// Forwards each player's own changes to `announce()`, as `HybridAudioBrowser` does.
  private let callbacks = MockPlayerCallbacks()

  init(_ heard: Heard = Heard()) {
    self.heard = heard
    callbacks.onSettingChanged = { [unowned self] in state.announce() }
  }

  func start(repeatMode: RepeatMode? = nil, playWhenReady: Bool? = nil) {
    state.setupStarted(repeatMode: repeatMode, playWhenReady: playWhenReady)
  }

  /// A setup's rebuild: the built player becomes the current one, then the window closes.
  @discardableResult
  func rebuild(_ built: Player = makePlayer()) -> Player {
    built.callbacks = callbacks
    player = built
    state.setupFinished(player: built)
    return built
  }

  /// A setup that a JS reload dropped before it built a player.
  func giveUp() {
    state.setupFinished(player: nil)
  }

  /// A setup that names no option, and the player it builds.
  func setUp(_ built: Player = makePlayer()) -> Player {
    start()
    return rebuild(built)
  }
}

@MainActor
private func playingPlayer() throws -> Player {
  let engine = MockPlayerEngine()
  let player = makePlayer(engine: engine)
  try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")])
  player.setPlayWhenReady(true)
  engine.emit(.itemPrepared(likelyToKeepUp: true))
  player.transition(.avPlayerPlaying)
  return player
}

@MainActor
private func failedPlayer() throws -> Player {
  let engine = MockPlayerEngine()
  let player = makePlayer(engine: engine)
  try player.setQueue([Track(id: "t1", src: "https://example.com/a.mp3", title: "T")])
  engine.emit(.itemPrepared(likelyToKeepUp: false))
  player.transition(.errorOccurred(.playbackFailed))
  return player
}

@MainActor
struct StagedPlayerStateTests {
  @Test func repeatModeSetDuringResetupEndsOnTheNewPlayer() {
    let staged = Staged()
    let old = staged.setUp()

    staged.start()
    staged.state.setRepeatMode(.track)
    #expect(old.repeatMode == .off)
    #expect(staged.state.repeatMode() == .track)

    let new = staged.rebuild()
    #expect(new.repeatMode == .track)
    #expect(staged.state.repeatMode() == .track)
  }

  @Test func playWhenReadySetDuringResetupEndsOnTheNewPlayer() {
    let staged = Staged()
    let old = staged.setUp()

    staged.start()
    staged.state.setPlayWhenReady(true)
    #expect(!old.playWhenReady)
    #expect(staged.state.playWhenReady())

    let new = staged.rebuild()
    #expect(new.playWhenReady)
    #expect(staged.state.playWhenReady())
  }

  @Test func valuesStagedBeforeTheFirstSetupReadBack() {
    let staged = Staged()
    staged.state.setRepeatMode(.track)
    staged.state.setPlayWhenReady(true)
    #expect(staged.state.repeatMode() == .track)
    #expect(staged.state.playWhenReady())
  }

  @Test func aToggleBeforeTheFirstSetupFlipsTheStagedIntent() {
    let staged = Staged()
    staged.state.togglePlayback()
    #expect(staged.state.playWhenReady())
    staged.state.togglePlayback()
    #expect(!staged.state.playWhenReady())
  }

  @Test func aSetupStagesItsOptionsBeforeALaterSetter() {
    let staged = Staged()
    _ = staged.setUp()

    staged.start(repeatMode: .queue, playWhenReady: true)
    #expect(staged.state.repeatMode() == .queue)
    staged.state.setRepeatMode(.track)

    let new = staged.rebuild()
    #expect(new.repeatMode == .track)
    #expect(new.playWhenReady)
  }

  @Test func aSetupsPauseOptionReachesTheOldPlayer() throws {
    let staged = Staged()
    let old = try staged.setUp(playingPlayer())

    staged.start(playWhenReady: false)
    #expect(!old.playWhenReady)
  }

  @Test func queuedSetupsApplyOnlyAtTheLastRebuild() {
    let staged = Staged()
    _ = staged.setUp()
    staged.start()
    staged.start()
    staged.state.setPlayWhenReady(true)

    let first = staged.rebuild()
    #expect(!first.playWhenReady)
    #expect(staged.state.playWhenReady())

    let last = staged.rebuild()
    #expect(last.playWhenReady)
  }

  @Test func aSetterAfterTheRebuildReachesThePlayer() {
    let staged = Staged()
    staged.start(repeatMode: .track)
    let player = staged.rebuild()

    staged.state.setRepeatMode(.queue)
    #expect(player.repeatMode == .queue)

    staged.start()
    let next = staged.rebuild()
    #expect(next.repeatMode == .off)
  }

  @Test func aPauseDuringResetupReachesTheOldPlayerToo() throws {
    let staged = Staged()
    let old = try staged.setUp(playingPlayer())

    staged.start()
    staged.state.setPlayWhenReady(false)
    #expect(!old.playWhenReady)

    let new = staged.rebuild()
    #expect(!new.playWhenReady)
  }

  @Test func aToggleDuringResetupPausesThePlayingOldPlayer() throws {
    let staged = Staged()
    let old = try staged.setUp(playingPlayer())

    staged.start()
    staged.state.togglePlayback()
    #expect(!old.playWhenReady)

    let new = staged.rebuild()
    #expect(!new.playWhenReady)
  }

  @Test func aStopDuringResetupDropsTheStagedPlay() {
    let staged = Staged()
    _ = staged.setUp()

    staged.start()
    staged.state.setPlayWhenReady(true)
    staged.state.stop()
    #expect(!staged.state.playWhenReady())

    let new = staged.rebuild()
    #expect(!new.playWhenReady)
  }

  @Test func isPlayingReadsTheStagedIntent() throws {
    let staged = Staged()
    _ = try staged.setUp(playingPlayer())

    staged.start()
    staged.state.setPlayWhenReady(false)
    #expect(!staged.state.isPlaying())
    staged.state.setPlayWhenReady(true)
    #expect(staged.state.isPlaying())
  }

  @Test func aToggleTakesBackAStagedPlayOnAFailedPlayer() throws {
    let staged = Staged()
    _ = try staged.setUp(failedPlayer())

    staged.start()
    staged.state.setPlayWhenReady(true)
    staged.state.togglePlayback()
    #expect(!staged.state.playWhenReady())
  }

  @Test func aStagedChangeIsHeardAtOnceAndOnlyOnce() {
    let staged = Staged()
    _ = staged.setUp()

    staged.start()
    staged.state.setRepeatMode(.track)
    staged.state.setPlayWhenReady(true)
    #expect(staged.heard.repeatModes == [.track])
    #expect(staged.heard.intents == [true])

    staged.rebuild()
    staged.state.announce()
    #expect(staged.heard.repeatModes == [.track])
    #expect(staged.heard.intents == [true])
  }

  @Test func aRebuildIsHeardWhereTheNewPlayerDiffers() {
    let staged = Staged()
    _ = staged.setUp()
    staged.state.setRepeatMode(.track)

    staged.start()
    staged.rebuild()
    #expect(staged.heard.repeatModes == [.track, .off])
  }

  @Test func aQueuedRebuildIsSilentWhileAValueIsStaged() {
    let staged = Staged()
    _ = staged.setUp()
    staged.start()
    staged.start()
    staged.state.setRepeatMode(.track)

    staged.rebuild()
    #expect(staged.heard.repeatModes == [.track])
    staged.rebuild()
    #expect(staged.heard.repeatModes == [.track])
  }

  @Test func aSetupDroppedAfterAQueuedRebuildIsHeardWhereTheGetterMoves() {
    let staged = Staged()
    _ = staged.setUp()
    staged.start()
    staged.start()
    staged.state.setRepeatMode(.track)

    let first = staged.rebuild()
    staged.giveUp()
    #expect(first.repeatMode == .off)
    #expect(staged.state.repeatMode() == .off)
    #expect(staged.heard.repeatModes == [.track, .off])
  }

  @Test func aPlayersOwnChangeIsHeardDuringAndAfterAWindow() throws {
    let staged = Staged()
    let old = try staged.setUp(playingPlayer())
    #expect(staged.heard.intents == [true])

    staged.start()
    staged.state.setRepeatMode(.track)
    old.handleInterruptionBegan()
    #expect(staged.heard.intents == [true, false])

    let new = try staged.rebuild(playingPlayer())
    #expect(staged.heard.intents == [true, false, true])
    new.handleInterruptionBegan()
    #expect(staged.heard.intents == [true, false, true, false])
    #expect(staged.heard.repeatModes == [.track])
  }

  @Test func aSetterThatReachesThePlayerIsHeardOnce() {
    let staged = Staged()
    let player = staged.setUp()

    staged.state.setRepeatMode(.track)
    staged.state.setPlayWhenReady(true)
    #expect(player.repeatMode == .track)
    #expect(staged.heard.repeatModes == [.track])
    #expect(staged.heard.intents == [true])
  }

  @Test func anOldPlayersLateEventIsNotHeardOverAStagedValue() throws {
    let staged = Staged()
    let old = try staged.setUp(playingPlayer())

    staged.start()
    staged.state.setPlayWhenReady(true)
    old.tearDown()
    old.stop()
    #expect(staged.heard.intents == [true])

    let new = staged.rebuild()
    #expect(new.playWhenReady)
    #expect(staged.heard.intents == [true])
  }
}
