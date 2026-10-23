import Testing

@testable import AudioBrowserTestable

@Suite("Player.playingState")
@MainActor
struct PlayingStateTests {
  private func state(_ playWhenReady: Bool, _ state: PlaybackState) -> PlayingState {
    Player.playingState(playWhenReady: playWhenReady, state: state)
  }

  @Test func playingWithIntentInAnActiveState() {
    #expect(state(true, .playing).playing)
    #expect(!state(true, .playing).buffering)
  }

  @Test func bufferingWithIntentWhileLoading() {
    #expect(state(true, .loading).playing)
    #expect(state(true, .loading).buffering)
  }

  @Test func notPlayingWithoutIntent() {
    #expect(!state(false, .playing).playing)
  }

  /// .stopped was once missing here, masked only by stop() clearing the intent right after.
  @Test func notPlayingInTerminalStatesEvenWithIntent() {
    for terminal in [PlaybackState.none, .stopped, .ended, .error] {
      #expect(!state(true, terminal).playing, "Expected not playing in \(terminal) despite intent")
    }
  }

  @Test func aChangeIsPublishedOnce() {
    let callbacks = MockPlayerCallbacks()
    let player = makePlayer()
    player.callbacks = callbacks
    player.transition(.avPlayerPlaying)
    player.setPlayWhenReady(true)
    player.setPlayWhenReady(true)
    #expect(callbacks.playingStateChanges.count == 1)
  }
}
