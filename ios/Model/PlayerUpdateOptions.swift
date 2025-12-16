import Foundation
import NitroModules

/**
 * Update options for the TrackPlayer that can be changed at runtime.
 * These options control player behavior and capabilities that can be modified during playback.
 */
class PlayerUpdateOptions {
  // MARK: - Core Properties

  /// Jump intervals
  var forwardJumpInterval: Double = 15.0
  var backwardJumpInterval: Double = 15.0
  var progressUpdateEventInterval: Double?

  /// Player capabilities - most enabled by default, only false values disable
  /// Exceptions: bookmark, jumpForward, jumpBackward default to false
  var capabilities: PlayerCapabilities = .init(
    play: nil, pause: nil, stop: nil, seekTo: nil,
    skipToNext: nil, skipToPrevious: nil,
    jumpForward: false, jumpBackward: false,
    favorite: nil, bookmark: false,
    shuffleMode: nil, repeatMode: nil, playbackRate: nil
  )

  /// Repeat mode
  var repeatMode: RepeatMode = .off

  /// iOS-specific options
  var likeOptions: FeedbackOptions = .init(isActive: false, title: "Like")
  var dislikeOptions: FeedbackOptions = .init(isActive: false, title: "Dislike")
  var bookmarkOptions: FeedbackOptions = .init(isActive: false, title: "Bookmark")

  /// Supported playback rates for the playback-rate capability
  var playbackRates: [Double] = [0.5, 1.0, 1.5, 2.0]

  // MARK: - Initialization

  init() {}

  // MARK: - Update from Nitro Options

  /// Update options from NativeUpdateOptions
  func update(from options: NativeUpdateOptions) {
    // Update jump intervals
    if let interval = options.forwardJumpInterval {
      forwardJumpInterval = interval
    }
    if let interval = options.backwardJumpInterval {
      backwardJumpInterval = interval
    }
    // progressUpdateEventInterval is a Variant_NullType_Double - extract the Double
    if let interval = options.progressUpdateEventInterval {
      switch interval {
      case .first:
        // null - disable progress updates
        progressUpdateEventInterval = nil
      case let .second(value):
        progressUpdateEventInterval = value
      }
    }

    // Update capabilities - merge incoming with existing, only explicitly set values override
    if let caps = options.capabilities {
      capabilities = mergeCapabilities(existing: capabilities, incoming: caps)
    }

    // Update iOS-specific options
    if let iosOptions = options.ios {
      if let like = iosOptions.likeOptions {
        likeOptions = like
      }
      if let dislike = iosOptions.dislikeOptions {
        dislikeOptions = dislike
      }
      if let bookmark = iosOptions.bookmarkOptions {
        bookmarkOptions = bookmark
      }
    }

    // Update playback rates
    if let rates = options.iosPlaybackRates {
      playbackRates = rates
    }
  }

  /// Convert to Nitro Options struct (full options with all required fields)
  func toOptions() -> Options {
    // Convert Double? to Variant_NullType_Double?
    let progressInterval: Variant_NullType_Double? = progressUpdateEventInterval.map { .second($0) }

    return Options(
      android: nil,
      ios: IOSOptions(
        likeOptions: likeOptions,
        dislikeOptions: dislikeOptions,
        bookmarkOptions: bookmarkOptions,
      ),
      forwardJumpInterval: forwardJumpInterval,
      backwardJumpInterval: backwardJumpInterval,
      progressUpdateEventInterval: progressInterval,
      capabilities: capabilities,
      repeatMode: repeatMode,
    )
  }

  /// Convert to Nitro UpdateOptions struct (partial options for getOptions())
  func toUpdateOptions() -> UpdateOptions {
    // Convert Double? to Variant_NullType_Double?
    let progressInterval: Variant_NullType_Double? = progressUpdateEventInterval.map { .second($0) }

    return UpdateOptions(
      android: nil,
      ios: IOSUpdateOptions(
        likeOptions: likeOptions,
        dislikeOptions: dislikeOptions,
        bookmarkOptions: bookmarkOptions,
      ),
      forwardJumpInterval: forwardJumpInterval,
      backwardJumpInterval: backwardJumpInterval,
      progressUpdateEventInterval: progressInterval,
      capabilities: capabilities,
      iosPlaybackRates: playbackRates,
    )
  }

  /// Merge incoming capabilities with existing - only explicitly set values override
  private func mergeCapabilities(existing: PlayerCapabilities, incoming: PlayerCapabilities) -> PlayerCapabilities {
    return PlayerCapabilities(
      play: incoming.play ?? existing.play,
      pause: incoming.pause ?? existing.pause,
      stop: incoming.stop ?? existing.stop,
      seekTo: incoming.seekTo ?? existing.seekTo,
      skipToNext: incoming.skipToNext ?? existing.skipToNext,
      skipToPrevious: incoming.skipToPrevious ?? existing.skipToPrevious,
      jumpForward: incoming.jumpForward ?? existing.jumpForward,
      jumpBackward: incoming.jumpBackward ?? existing.jumpBackward,
      favorite: incoming.favorite ?? existing.favorite,
      bookmark: incoming.bookmark ?? existing.bookmark,
      shuffleMode: incoming.shuffleMode ?? existing.shuffleMode,
      repeatMode: incoming.repeatMode ?? existing.repeatMode,
      playbackRate: incoming.playbackRate ?? existing.playbackRate
    )
  }
}
