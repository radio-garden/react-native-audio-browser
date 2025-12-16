import AVFoundation
import Foundation

/**
 Observes time-based player events and invokes callbacks passed at initialization.
 */
class PlayerTimeObserver {
  /// The time to use as start boundary time. Cannot be zero.
  private static let startBoundaryTime: CMTime = .init(value: 1, timescale: 1000)

  var boundaryTimeStartObserverToken: Any?
  var periodicTimeObserverToken: Any?

  weak var avPlayer: AVPlayer? {
    willSet {
      unregisterForBoundaryTimeEvents()
      unregisterForPeriodicEvents()
    }
  }

  /// The frequency to receive periodic time events.
  /// Setting this to a new value will trigger a re-registering to the periodic events of the
  /// player.
  var periodicObserverTimeInterval: CMTime {
    didSet {
      if oldValue != periodicObserverTimeInterval {
        registerForPeriodicTimeEvents()
      }
    }
  }

  private let onAudioDidStart: @Sendable () -> Void
  private let onSecondElapsed: @Sendable (Double) -> Void

  init(
    periodicObserverTimeInterval: CMTime,
    onAudioDidStart: @escaping @Sendable () -> Void,
    onSecondElapsed: @escaping @Sendable (Double) -> Void,
  ) {
    self.periodicObserverTimeInterval = periodicObserverTimeInterval
    self.onAudioDidStart = onAudioDidStart
    self.onSecondElapsed = onSecondElapsed
  }

  deinit {
    unregisterForPeriodicEvents()
    unregisterForBoundaryTimeEvents()
  }

  /**
   Will register for the AVPlayer BoundaryTimeEvents, to trigger start and complete events.
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
        self?.onAudioDidStart()
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

  /**
   Start observing periodic time events.
   Will trigger unregisterForPeriodicEvents() first to avoid multiple subscriptions.
   */
  func registerForPeriodicTimeEvents() {
    guard let avPlayer else {
      return
    }
    unregisterForPeriodicEvents()
    periodicTimeObserverToken = avPlayer.addPeriodicTimeObserver(
      forInterval: periodicObserverTimeInterval,
      queue: nil,
      using: { [weak self] time in
        self?.onSecondElapsed(time.seconds)
      },
    )
  }

  /**
   Unregister for periodic events.
   */
  func unregisterForPeriodicEvents() {
    guard let avPlayer, let periodicTimeObserverToken else {
      return
    }
    avPlayer.removeTimeObserver(periodicTimeObserverToken)
    self.periodicTimeObserverToken = nil
  }
}
