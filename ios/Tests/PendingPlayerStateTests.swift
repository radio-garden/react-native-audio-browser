@testable import AudioBrowserTestable
import Testing

@Suite("PendingPlayerState")
@MainActor
struct PendingPlayerStateTests {
  @Test func appliesShuffleHeldBeforeThePlayerExisted() {
    let player = makePlayer(engine: MockPlayerEngine())
    var pending = PendingPlayerState()
    pending.shuffleEnabled = true

    pending.apply(to: player)

    #expect(player.shuffleEnabled)
  }

  @Test func appliesRepeatModeAndPlayWhenReady() {
    let player = makePlayer(engine: MockPlayerEngine())
    var pending = PendingPlayerState()
    pending.repeatMode = .queue
    pending.playWhenReady = true

    pending.apply(to: player)

    #expect(player.repeatMode == .queue)
    #expect(player.playWhenReady)
  }

  @Test func applyConsumesTheState() {
    var pending = PendingPlayerState()
    pending.shuffleEnabled = true
    pending.repeatMode = .track
    pending.playWhenReady = true

    pending.apply(to: makePlayer(engine: MockPlayerEngine()))
    let later = makePlayer(engine: MockPlayerEngine())
    pending.apply(to: later)

    #expect(!later.shuffleEnabled)
    #expect(later.repeatMode == .off)
    #expect(!later.playWhenReady)
  }
}
