import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("PlayingStateManager")
struct PlayingStateManagerTests {
  @MainActor
  private func makeManager() -> (manager: PlayingStateManager, changes: () -> [PlayingState]) {
    var recorded: [PlayingState] = []
    let manager = PlayingStateManager { recorded.append($0) }
    return (manager, { recorded })
  }

  @Test @MainActor
  func playing_whenIntentAndActiveState() {
    let (m, _) = makeManager()
    m.update(playWhenReady: true, state: .playing)
    #expect(m.playing == true)
    #expect(m.buffering == false)
  }

  @Test @MainActor
  func buffering_whenIntentAndLoading() {
    let (m, _) = makeManager()
    m.update(playWhenReady: true, state: .loading)
    #expect(m.playing == true)
    #expect(m.buffering == true)
  }

  @Test @MainActor
  func notPlaying_withoutIntent() {
    let (m, _) = makeManager()
    m.update(playWhenReady: false, state: .playing)
    #expect(m.playing == false)
  }

  /// Terminal states are not playable regardless of intent. .stopped was
  /// missing from the exclusion list — masked only by stop() clearing
  /// playWhenReady right after transitioning, which made the gap latent.
  @Test @MainActor
  func notPlaying_inTerminalStates_evenWithIntent() {
    for state in [PlaybackState.none, .stopped, .ended, .error] {
      let (m, _) = makeManager()
      m.update(playWhenReady: true, state: state)
      #expect(m.playing == false, "Expected not playing in \(state) despite intent")
    }
  }

  @Test @MainActor
  func onChange_firesOnlyOnActualChange() {
    let (m, changes) = makeManager()
    m.update(playWhenReady: true, state: .playing)
    m.update(playWhenReady: true, state: .playing)
    #expect(changes().count == 1)
  }
}
