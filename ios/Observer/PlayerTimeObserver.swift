import AVFoundation
import Foundation

/**
 Observes the player's audio-start boundary and invokes the callback passed at initialization.
 */
@MainActor class PlayerTimeObserver {
  /// The time to use as start boundary time. Cannot be zero.
  private static let startBoundaryTime: CMTime = .init(value: 1, timescale: 1000)

  var boundaryTimeStartObserverToken: Any?

  weak var avPlayer: AVPlayer? {
    willSet {
      unregisterForBoundaryTimeEvents()
    }
  }

  private let onAudioDidStart: @MainActor () -> Void

  init(onAudioDidStart: @escaping @MainActor () -> Void) {
    self.onAudioDidStart = onAudioDidStart
  }

  /**
   Will register for the AVPlayer BoundaryTimeEvents, to trigger the audio-start event.
   */
  func registerForBoundaryTimeEvents() {
    guard let avPlayer else {
      return
    }
    unregisterForBoundaryTimeEvents()
    boundaryTimeStartObserverToken = avPlayer.addBoundaryTimeObserver(
      forTimes: [PlayerTimeObserver.startBoundaryTime].map {
        NSValue(time: $0)
      },
      queue: nil,
      using: { [weak self] in
        Task { @MainActor in self?.onAudioDidStart() }
      },
    )
  }

  /**
   Unregister from the boundary events of the player.
   */
  func unregisterForBoundaryTimeEvents() {
    guard
      let avPlayer,
      let boundaryTimeStartObserverToken
    else { return }
    avPlayer.removeTimeObserver(boundaryTimeStartObserverToken)
    self.boundaryTimeStartObserverToken = nil
  }
}
