import Foundation
import NitroModules

/**
 Manages periodic progress updates during playback.
 Emits progress events at configurable intervals based on playback state.
 */
class PlaybackProgressUpdateManager {
  private var timer: Timer?
  private var updateInterval: TimeInterval?
  private let onProgressUpdate: () -> Void

  init(onProgressUpdate: @escaping () -> Void) {
    self.onProgressUpdate = onProgressUpdate
  }

  deinit {
    stop()
  }

  func setUpdateInterval(_ interval: TimeInterval?) {
    if interval == updateInterval { return }
    updateInterval = (interval != nil && interval! > 0) ? interval : nil
    stop()
    if updateInterval != nil {
      start()
    }
  }

  func start() {
    guard timer == nil, let interval = updateInterval else { return }
    timer = Timer.scheduledTimer(
      withTimeInterval: interval,
      repeats: true,
    ) { [weak self] _ in
      self?.onProgressUpdate()
    }
  }

  func stop() {
    timer?.invalidate()
    timer = nil
  }

  func onPlaybackStateChanged(_ state: PlaybackState) {
    switch state {
    // Start when playback is set to resume (loading, buffering) or playing
    case .loading, .buffering, .playing:
      start()

    // Stop when playback pauses, stops, or errors
    case .paused, .stopped, .ended, .error:
      stop()

    // No action for ready, none
    default:
      break
    }
  }
}
