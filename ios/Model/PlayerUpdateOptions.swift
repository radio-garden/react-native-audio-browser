import Foundation
import NitroModules

/**
 * Update options for the TrackPlayer that can be changed at runtime.
 * These options control player behavior and capabilities that can be modified during playback.
 */
class PlayerUpdateOptions {
  // MARK: - Core Properties

  /// Jump intervals
  public var forwardJumpInterval: Double = 15.0
  public var backwardJumpInterval: Double = 15.0
  public var progressUpdateEventInterval: Double?

  /// Rating and capabilities
  public var capabilities: [Capability] = [
    .play,
    .pause,
    .skipToNext,
    .skipToPrevious,
    .seekTo,
  ]

  /// Repeat mode
  public var repeatMode: RepeatMode = .off

  /// iOS-specific options
  public var likeOptions: FeedbackOptions = .init(isActive: false, title: "Like")
  public var dislikeOptions: FeedbackOptions = .init(isActive: false, title: "Dislike")
  public var bookmarkOptions: FeedbackOptions = .init(isActive: false, title: "Bookmark")

  // MARK: - Initialization

  public init() {}

  // MARK: - Update from Nitro Options

  /// Update options from NativeUpdateOptions
  public func update(from options: NativeUpdateOptions) {
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
      case .second(let value):
        progressUpdateEventInterval = value
      }
    }

    // Update capabilities
    if let caps = options.capabilities {
      capabilities = caps
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
  }

  /// Convert to Nitro Options struct (full options with all required fields)
  public func toOptions() -> Options {
    // Convert Double? to Variant_NullType_Double?
    let progressInterval: Variant_NullType_Double? = progressUpdateEventInterval.map { .second($0) }

    return Options(
      android: nil,
      ios: IOSOptions(
        likeOptions: likeOptions,
        dislikeOptions: dislikeOptions,
        bookmarkOptions: bookmarkOptions
      ),
      forwardJumpInterval: forwardJumpInterval,
      backwardJumpInterval: backwardJumpInterval,
      progressUpdateEventInterval: progressInterval,
      capabilities: capabilities,
      repeatMode: repeatMode
    )
  }

  /// Convert to Nitro UpdateOptions struct (partial options for getOptions())
  public func toUpdateOptions() -> UpdateOptions {
    // Convert Double? to Variant_NullType_Double?
    let progressInterval: Variant_NullType_Double? = progressUpdateEventInterval.map { .second($0) }

    return UpdateOptions(
      android: nil,
      ios: IOSUpdateOptions(
        likeOptions: likeOptions,
        dislikeOptions: dislikeOptions,
        bookmarkOptions: bookmarkOptions
      ),
      forwardJumpInterval: forwardJumpInterval,
      backwardJumpInterval: backwardJumpInterval,
      progressUpdateEventInterval: progressInterval,
      capabilities: capabilities
    )
  }
}
