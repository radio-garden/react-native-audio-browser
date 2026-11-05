import Foundation
import NitroModules

class PlayerUpdateOptions {
  // MARK: - Core Properties

  var forwardJumpInterval: Double = 15.0
  var backwardJumpInterval: Double = 15.0
  var progressUpdateEventInterval: Double?
  // Set through `setPlaybackIntervalEnabled`; neither `update(from:)` nor `toOptions()` touches it.
  var playbackIntervalEnabled = false

  // An unset capability counts as enabled (`remoteCommands`), except favorite, so the jump
  // commands start explicitly disabled.
  var capabilities: PlayerCapabilities = .init(
    play: nil, pause: nil, stop: nil, seekTo: nil,
    skipToNext: nil, skipToPrevious: nil,
    jumpForward: false, jumpBackward: false,
    favorite: nil,
    shuffleMode: nil, repeatMode: nil, playbackRate: nil,
  )

  var playbackRates: [Double] = [0.5, 1.0, 1.5, 2.0]

  var carPlayUpNextButton: Bool = true

  var carPlayNowPlayingButtons: [CarPlayNowPlayingButton] = []

  var carPlayPlayingIndicatorLocation: CarPlayPlayingIndicatorLocation = .leading

  // MARK: - Initialization

  init() {}

  // MARK: - Update from Nitro Options

  /// Applies only the fields `options` sets. Capabilities merge field by field, so an unset
  /// capability keeps its value; a null progress interval turns progress events off.
  func update(from options: NativeUpdateOptions) {
    if let interval = options.forwardJumpInterval {
      forwardJumpInterval = interval
    }
    if let interval = options.backwardJumpInterval {
      backwardJumpInterval = interval
    }
    if let interval = options.progressUpdateEventInterval {
      switch interval {
      case .first:
        progressUpdateEventInterval = nil
      case let .second(value):
        progressUpdateEventInterval = value
      }
    }

    if let caps = options.capabilities {
      capabilities = mergeCapabilities(existing: capabilities, incoming: caps)
    }

    if let ios = options.ios {
      if let rates = ios.playbackRates {
        playbackRates = rates
      }
      if let upNext = ios.carPlayUpNextButton {
        carPlayUpNextButton = upNext
      }
      if let buttons = ios.carPlayNowPlayingButtons {
        carPlayNowPlayingButtons = buttons
      }
      if let location = ios.carPlayPlayingIndicatorLocation {
        carPlayPlayingIndicatorLocation = location
      }
    }
  }

  /// The options in their wire shape, as `getOptions` and `onOptionsChanged` report them.
  func toOptions() -> Options {
    let progressInterval: Variant_NullType_Double? = progressUpdateEventInterval.map { .second($0) }

    return Options(
      android: nil,
      forwardJumpInterval: forwardJumpInterval,
      backwardJumpInterval: backwardJumpInterval,
      progressUpdateEventInterval: progressInterval,
      capabilities: capabilities,
      ios: IOSOptions(
        playbackRates: playbackRates,
        carPlayUpNextButton: carPlayUpNextButton,
        carPlayNowPlayingButtons: carPlayNowPlayingButtons,
        carPlayPlayingIndicatorLocation: carPlayPlayingIndicatorLocation,
      ),
    )
  }

  private func mergeCapabilities(existing: PlayerCapabilities, incoming: PlayerCapabilities) -> PlayerCapabilities {
    PlayerCapabilities(
      play: incoming.play ?? existing.play,
      pause: incoming.pause ?? existing.pause,
      stop: incoming.stop ?? existing.stop,
      seekTo: incoming.seekTo ?? existing.seekTo,
      skipToNext: incoming.skipToNext ?? existing.skipToNext,
      skipToPrevious: incoming.skipToPrevious ?? existing.skipToPrevious,
      jumpForward: incoming.jumpForward ?? existing.jumpForward,
      jumpBackward: incoming.jumpBackward ?? existing.jumpBackward,
      favorite: incoming.favorite ?? existing.favorite,
      shuffleMode: incoming.shuffleMode ?? existing.shuffleMode,
      repeatMode: incoming.repeatMode ?? existing.repeatMode,
      playbackRate: incoming.playbackRate ?? existing.playbackRate,
    )
  }
}
