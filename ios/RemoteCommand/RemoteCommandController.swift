import Foundation
import MediaPlayer
import os.log

typealias RemoteCommandHandler = @MainActor (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus

extension RepeatMode {
  /// Convert to MPRepeatType for MPRemoteCommandCenter sync
  var mpRepeatType: MPRepeatType {
    switch self {
    case .off: .off
    case .track: .one
    case .queue: .all
    }
  }

  /// Create from MPRepeatType
  init(from mpRepeatType: MPRepeatType) {
    switch mpRepeatType {
    case .off: self = .off
    case .one: self = .track
    case .all: self = .queue
    @unknown default: self = .off
    }
  }
}

extension Bool {
  /// Convert to MPShuffleType for MPRemoteCommandCenter sync
  var mpShuffleType: MPShuffleType {
    self ? .items : .off
  }
}

/**
 Manages MPRemoteCommandCenter integration for media control (lock screen, control center, CarPlay, etc.).

 This controller enables/disables remote commands and routes them to handlers. It provides default handlers
 that invoke TrackPlayerCallbacks, but allows customization by setting the lazy handler properties.

 Note: This class is @MainActor because it's owned by TrackPlayer (which is @MainActor) and
 MPRemoteCommandCenter handlers are always called on the main thread.
 */
@MainActor
class RemoteCommandController {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "RemoteCommandController")
  private var center: MPRemoteCommandCenter

  weak var callbacks: TrackPlayerCallbacks?

  var commandTargetPointers: [String: Any] = [:]
  private var enabledCommands: [RemoteCommand] = []

  /**
   Create a new RemoteCommandController.

   - parameter remoteCommandCenter: The MPRemoteCommandCenter used. Default is `MPRemoteCommandCenter.shared()`
   - parameter callbacks: The callbacks to invoke for remote command events
   */
  init(
    remoteCommandCenter: MPRemoteCommandCenter = MPRemoteCommandCenter.shared(),
    callbacks: TrackPlayerCallbacks? = nil,
  ) {
    center = remoteCommandCenter
    self.callbacks = callbacks
  }

  /**
   Switches to a new remote command center.
   This is used when MPNowPlayingSession is created/destroyed on iOS 16+,
   as the session has its own command center that must be used instead of the shared one.

   - parameter newCenter: The new MPRemoteCommandCenter to use
   */
  func switchCommandCenter(_ newCenter: MPRemoteCommandCenter) {
    guard newCenter !== center else {
      logger.debug("switchCommandCenter: same command center, skipping")
      return
    }

    logger.info("Switching remote command center")

    // Disable all commands on the old center
    let commandsToReEnable = enabledCommands
    disable(commands: commandsToReEnable)

    // Switch to new center
    center = newCenter

    // Re-enable commands on the new center
    enable(commands: commandsToReEnable)

    logger.info("Switched command center, re-enabled \(commandsToReEnable.count) commands")
  }

  func enable(commands: [RemoteCommand]) {
    let commandsToDisable = enabledCommands.filter { command in
      !commands.contains(command)
    }

    enabledCommands = commands
    commands.forEach { self.enable(command: $0) }
    disable(commands: commandsToDisable)
  }

  func disable(commands: [RemoteCommand]) {
    commands.forEach { self.disable(command: $0) }
  }

  /**
   Enables a remote command by setting it up with the provided handler.
   Removes any existing target before adding the new one to prevent duplicate handlers.
   */
  private func enableRemoteCommand(
    _ command: MPRemoteCommand,
    key: String,
    handler: @escaping RemoteCommandHandler,
  ) {
    command.isEnabled = true
    command.removeTarget(commandTargetPointers[key])
    commandTargetPointers[key] = command.addTarget(handler: handler)
  }

  /**
   Disables a remote command and cleans up its target from the command center.
   */
  private func disableRemoteCommand(_ command: MPRemoteCommand, key: String) {
    command.isEnabled = false
    command.removeTarget(commandTargetPointers[key])
    commandTargetPointers.removeValue(forKey: key)
  }

  private func enable(command: RemoteCommand) {
    switch command {
    case .play:
      enableRemoteCommand(center.playCommand, key: command.key, handler: handlePlayCommand)
    case .pause:
      enableRemoteCommand(center.pauseCommand, key: command.key, handler: handlePauseCommand)
    case .stop:
      enableRemoteCommand(center.stopCommand, key: command.key, handler: handleStopCommand)
    case .togglePlayPause:
      enableRemoteCommand(
        center.togglePlayPauseCommand,
        key: command.key,
        handler: handleTogglePlayPauseCommand,
      )
    case .next:
      enableRemoteCommand(
        center.nextTrackCommand,
        key: command.key,
        handler: handleNextTrackCommand,
      )
    case .previous:
      enableRemoteCommand(
        center.previousTrackCommand,
        key: command.key,
        handler: handlePreviousTrackCommand,
      )
    case .changePlaybackPosition:
      enableRemoteCommand(
        center.changePlaybackPositionCommand,
        key: command.key,
        handler: handleChangePlaybackPositionCommand,
      )
    case let .skipForward(preferredIntervals):
      center.skipForwardCommand.preferredIntervals = preferredIntervals
      enableRemoteCommand(
        center.skipForwardCommand,
        key: command.key,
        handler: handleSkipForwardCommand,
      )
    case let .skipBackward(preferredIntervals):
      center.skipBackwardCommand.preferredIntervals = preferredIntervals
      enableRemoteCommand(
        center.skipBackwardCommand,
        key: command.key,
        handler: handleSkipBackwardCommand,
      )
    case let .like(isActive, localizedTitle, localizedShortTitle):
      center.likeCommand.isActive = isActive
      center.likeCommand.localizedTitle = localizedTitle
      center.likeCommand.localizedShortTitle = localizedShortTitle
      enableRemoteCommand(center.likeCommand, key: command.key, handler: handleLikeCommand)
    case let .dislike(isActive, localizedTitle, localizedShortTitle):
      center.dislikeCommand.isActive = isActive
      center.dislikeCommand.localizedTitle = localizedTitle
      center.dislikeCommand.localizedShortTitle = localizedShortTitle
      enableRemoteCommand(center.dislikeCommand, key: command.key, handler: handleDislikeCommand)
    case let .bookmark(isActive, localizedTitle, localizedShortTitle):
      center.bookmarkCommand.isActive = isActive
      center.bookmarkCommand.localizedTitle = localizedTitle
      center.bookmarkCommand.localizedShortTitle = localizedShortTitle
      enableRemoteCommand(center.bookmarkCommand, key: command.key, handler: handleBookmarkCommand)
    case .changeRepeatMode:
      enableRemoteCommand(
        center.changeRepeatModeCommand,
        key: command.key,
        handler: handleChangeRepeatModeCommand,
      )
    case .changeShuffleMode:
      enableRemoteCommand(
        center.changeShuffleModeCommand,
        key: command.key,
        handler: handleChangeShuffleModeCommand,
      )
    case let .changePlaybackRate(supportedPlaybackRates):
      center.changePlaybackRateCommand.supportedPlaybackRates = supportedPlaybackRates
      enableRemoteCommand(
        center.changePlaybackRateCommand,
        key: command.key,
        handler: handleChangePlaybackRateCommand,
      )
    }
  }

  private func disable(command: RemoteCommand) {
    switch command {
    case .play:
      disableRemoteCommand(center.playCommand, key: command.key)
    case .pause:
      disableRemoteCommand(center.pauseCommand, key: command.key)
    case .stop:
      disableRemoteCommand(center.stopCommand, key: command.key)
    case .togglePlayPause:
      disableRemoteCommand(center.togglePlayPauseCommand, key: command.key)
    case .next:
      disableRemoteCommand(center.nextTrackCommand, key: command.key)
    case .previous:
      disableRemoteCommand(center.previousTrackCommand, key: command.key)
    case .changePlaybackPosition:
      disableRemoteCommand(center.changePlaybackPositionCommand, key: command.key)
    case .skipForward:
      disableRemoteCommand(center.skipForwardCommand, key: command.key)
    case .skipBackward:
      disableRemoteCommand(center.skipBackwardCommand, key: command.key)
    case .like:
      disableRemoteCommand(center.likeCommand, key: command.key)
    case .dislike:
      disableRemoteCommand(center.dislikeCommand, key: command.key)
    case .bookmark:
      disableRemoteCommand(center.bookmarkCommand, key: command.key)
    case .changeRepeatMode:
      disableRemoteCommand(center.changeRepeatModeCommand, key: command.key)
    case .changeShuffleMode:
      disableRemoteCommand(center.changeShuffleModeCommand, key: command.key)
    case .changePlaybackRate:
      disableRemoteCommand(center.changePlaybackRateCommand, key: command.key)
    }
  }

  // MARK: - Repeat/Shuffle State Sync

  /// Updates the repeat mode state shown on CarPlay and lock screen
  func updateRepeatMode(_ mode: RepeatMode) {
    center.changeRepeatModeCommand.currentRepeatType = mode.mpRepeatType
  }

  /// Updates the shuffle mode state shown on CarPlay and lock screen
  func updateShuffleMode(_ enabled: Bool) {
    center.changeShuffleModeCommand.currentShuffleType = enabled.mpShuffleType
  }

  // MARK: - Handlers

  lazy var handlePlayCommand: RemoteCommandHandler = handlePlayCommandDefault
  lazy var handlePauseCommand: RemoteCommandHandler = handlePauseCommandDefault
  lazy var handleStopCommand: RemoteCommandHandler = handleStopCommandDefault
  lazy var handleTogglePlayPauseCommand: RemoteCommandHandler =
    handleTogglePlayPauseCommandDefault
  lazy var handleSkipForwardCommand: RemoteCommandHandler = handleSkipForwardCommandDefault
  lazy var handleSkipBackwardCommand: RemoteCommandHandler = handleSkipBackwardDefault
  lazy var handleChangePlaybackPositionCommand: RemoteCommandHandler =
    handleChangePlaybackPositionCommandDefault
  lazy var handleNextTrackCommand: RemoteCommandHandler = handleNextTrackCommandDefault
  lazy var handlePreviousTrackCommand: RemoteCommandHandler =
    handlePreviousTrackCommandDefault
  lazy var handleLikeCommand: RemoteCommandHandler = handleLikeCommandDefault
  lazy var handleDislikeCommand: RemoteCommandHandler = handleDislikeCommandDefault
  lazy var handleBookmarkCommand: RemoteCommandHandler = handleBookmarkCommandDefault
  lazy var handleChangeRepeatModeCommand: RemoteCommandHandler =
    handleChangeRepeatModeCommandDefault
  lazy var handleChangeShuffleModeCommand: RemoteCommandHandler =
    handleChangeShuffleModeCommandDefault
  lazy var handleChangePlaybackRateCommand: RemoteCommandHandler =
    handleChangePlaybackRateCommandDefault

  private func handlePlayCommandDefault(event _: MPRemoteCommandEvent)
    -> MPRemoteCommandHandlerStatus
  {
    callbacks?.remotePlay()
    return MPRemoteCommandHandlerStatus.success
  }

  private func handlePauseCommandDefault(event _: MPRemoteCommandEvent)
    -> MPRemoteCommandHandlerStatus
  {
    callbacks?.remotePause()
    return MPRemoteCommandHandlerStatus.success
  }

  private func handleStopCommandDefault(event _: MPRemoteCommandEvent)
    -> MPRemoteCommandHandlerStatus
  {
    callbacks?.remoteStop()
    return MPRemoteCommandHandlerStatus.success
  }

  private func handleTogglePlayPauseCommandDefault(event _: MPRemoteCommandEvent)
    -> MPRemoteCommandHandlerStatus
  {
    callbacks?.remotePlayPause()
    return MPRemoteCommandHandlerStatus.success
  }

  private func handleSkipForwardCommandDefault(event: MPRemoteCommandEvent)
    -> MPRemoteCommandHandlerStatus
  {
    if let command = event.command as? MPSkipIntervalCommand,
       let interval = command.preferredIntervals.first
    {
      callbacks?.remoteJumpForward(interval: Double(truncating: interval))
      return MPRemoteCommandHandlerStatus.success
    }
    return MPRemoteCommandHandlerStatus.commandFailed
  }

  private func handleSkipBackwardDefault(event: MPRemoteCommandEvent)
    -> MPRemoteCommandHandlerStatus
  {
    if let command = event.command as? MPSkipIntervalCommand,
       let interval = command.preferredIntervals.first
    {
      callbacks?.remoteJumpBackward(interval: Double(truncating: interval))
      return MPRemoteCommandHandlerStatus.success
    }
    return MPRemoteCommandHandlerStatus.commandFailed
  }

  private func handleChangePlaybackPositionCommandDefault(event: MPRemoteCommandEvent)
    -> MPRemoteCommandHandlerStatus
  {
    if let event = event as? MPChangePlaybackPositionCommandEvent {
      logger.debug("changePlaybackPosition: \(event.positionTime)")
      callbacks?.remoteSeek(position: event.positionTime)
      return MPRemoteCommandHandlerStatus.success
    }
    logger.debug("changePlaybackPosition: failed to cast event")
    return MPRemoteCommandHandlerStatus.commandFailed
  }

  private func handleNextTrackCommandDefault(event _: MPRemoteCommandEvent)
    -> MPRemoteCommandHandlerStatus
  {
    callbacks?.remoteNext()
    return MPRemoteCommandHandlerStatus.success
  }

  private func handlePreviousTrackCommandDefault(event _: MPRemoteCommandEvent)
    -> MPRemoteCommandHandlerStatus
  {
    callbacks?.remotePrevious()
    return MPRemoteCommandHandlerStatus.success
  }

  private func handleLikeCommandDefault(event _: MPRemoteCommandEvent)
    -> MPRemoteCommandHandlerStatus
  {
    callbacks?.remoteLike()
    return MPRemoteCommandHandlerStatus.success
  }

  private func handleDislikeCommandDefault(event _: MPRemoteCommandEvent)
    -> MPRemoteCommandHandlerStatus
  {
    callbacks?.remoteDislike()
    return MPRemoteCommandHandlerStatus.success
  }

  private func handleBookmarkCommandDefault(event _: MPRemoteCommandEvent)
    -> MPRemoteCommandHandlerStatus
  {
    callbacks?.remoteBookmark()
    return MPRemoteCommandHandlerStatus.success
  }

  private func handleChangeRepeatModeCommandDefault(event: MPRemoteCommandEvent)
    -> MPRemoteCommandHandlerStatus
  {
    if let event = event as? MPChangeRepeatModeCommandEvent {
      let mode = RepeatMode(from: event.repeatType)
      callbacks?.remoteChangeRepeatMode(mode: mode)
      // Update the command center state to reflect the new mode
      center.changeRepeatModeCommand.currentRepeatType = event.repeatType
      return MPRemoteCommandHandlerStatus.success
    }
    return MPRemoteCommandHandlerStatus.commandFailed
  }

  private func handleChangeShuffleModeCommandDefault(event: MPRemoteCommandEvent)
    -> MPRemoteCommandHandlerStatus
  {
    if let event = event as? MPChangeShuffleModeCommandEvent {
      let enabled = event.shuffleType != .off
      callbacks?.remoteChangeShuffleMode(enabled: enabled)
      // Update the command center state to reflect the new mode
      center.changeShuffleModeCommand.currentShuffleType = event.shuffleType
      return MPRemoteCommandHandlerStatus.success
    }
    return MPRemoteCommandHandlerStatus.commandFailed
  }

  private func handleChangePlaybackRateCommandDefault(event: MPRemoteCommandEvent)
    -> MPRemoteCommandHandlerStatus
  {
    if let event = event as? MPChangePlaybackRateCommandEvent {
      let rate = Float(event.playbackRate)
      callbacks?.remoteChangePlaybackRate(rate: rate)
      return MPRemoteCommandHandlerStatus.success
    }
    return MPRemoteCommandHandlerStatus.commandFailed
  }

  private func getRemoteCommandHandlerStatus(forError error: Error)
    -> MPRemoteCommandHandlerStatus
  {
    error is TrackPlayerError.QueueError
      ? MPRemoteCommandHandlerStatus.noSuchContent
      : MPRemoteCommandHandlerStatus.commandFailed
  }
}
