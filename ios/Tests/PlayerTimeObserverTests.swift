import AVFoundation
import Testing

@testable import AudioBrowserTestable

@MainActor
@Suite("PlayerTimeObserver")
struct PlayerTimeObserverTests {
  /// An AVPlayer retains its time observers, so one left registered outlives both this object and
  /// the caller's interest in it (#96).
  @Test func unregisteringDropsTheToken() {
    // Held: `avPlayer` is weak, so a temporary is gone before the next line.
    let player = AVPlayer()
    let observer = PlayerTimeObserver(onAudioDidStart: {})
    observer.avPlayer = player

    observer.registerForBoundaryTimeEvents()
    #expect(observer.boundaryTimeStartObserverToken != nil)

    observer.unregisterForBoundaryTimeEvents()
    #expect(observer.boundaryTimeStartObserverToken == nil)
  }

  /// Swapping players has to unregister from the outgoing one — it is a different AVPlayer, and
  /// the token cannot be removed from it afterwards.
  @Test func swappingPlayersUnregistersFromThePrevious() {
    let first = AVPlayer()
    let second = AVPlayer()
    let observer = PlayerTimeObserver(onAudioDidStart: {})
    observer.avPlayer = first
    observer.registerForBoundaryTimeEvents()

    observer.avPlayer = second

    #expect(observer.boundaryTimeStartObserverToken == nil)
  }
}
