import Foundation
import MediaPlayer

typealias RemoteCommandHandler = (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus

/**
 Manages MPRemoteCommandCenter integration for media control (lock screen, control center, CarPlay, etc.).

 This controller enables/disables remote commands and routes them to handlers. It provides default handlers
 that invoke TrackPlayerCallbacks, but allows customization by setting the lazy handler properties.
 */
class RemoteCommandController {
  private let center: MPRemoteCommandCenter

  weak var callbacks: TrackPlayerCallbacks?

  var commandTargetPointers: [String: Any] = [:]
  private var enabledCommands: [RemoteCommand] = []

  /**
   Create a new RemoteCommandController.

   - parameter remoteCommandCenter: The MPRemoteCommandCenter used. Default is `MPRemoteCommandCenter.shared()`
   - parameter callbacks: The callbacks to invoke for remote command events
   */
  public init(
    remoteCommandCenter: MPRemoteCommandCenter = MPRemoteCommandCenter.shared(),
    callbacks: TrackPlayerCallbacks? = nil
  ) {
    center = remoteCommandCenter
    self.callbacks = callbacks
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
    handler: @escaping RemoteCommandHandler
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
        handler: handleTogglePlayPauseCommand
      )
    case .next:
      enableRemoteCommand(
        center.nextTrackCommand,
        key: command.key,
        handler: handleNextTrackCommand
      )
    case .previous:
      enableRemoteCommand(
        center.previousTrackCommand,
        key: command.key,
        handler: handlePreviousTrackCommand
      )
    case .changePlaybackPosition:
      enableRemoteCommand(
        center.changePlaybackPositionCommand,
        key: command.key,
        handler: handleChangePlaybackPositionCommand
      )
    case let .skipForward(preferredIntervals):
      center.skipForwardCommand.preferredIntervals = preferredIntervals
      enableRemoteCommand(
        center.skipForwardCommand,
        key: command.key,
        handler: handleSkipForwardCommand
      )
    case let .skipBackward(preferredIntervals):
      center.skipBackwardCommand.preferredIntervals = preferredIntervals
      enableRemoteCommand(
        center.skipBackwardCommand,
        key: command.key,
        handler: handleSkipBackwardCommand
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
    }
  }

  // MARK: - Handlers

  public lazy var handlePlayCommand: RemoteCommandHandler = handlePlayCommandDefault
  public lazy var handlePauseCommand: RemoteCommandHandler = handlePauseCommandDefault
  public lazy var handleStopCommand: RemoteCommandHandler = handleStopCommandDefault
  public lazy var handleTogglePlayPauseCommand: RemoteCommandHandler =
    handleTogglePlayPauseCommandDefault
  public lazy var handleSkipForwardCommand: RemoteCommandHandler = handleSkipForwardCommandDefault
  public lazy var handleSkipBackwardCommand: RemoteCommandHandler = handleSkipBackwardDefault
  public lazy var handleChangePlaybackPositionCommand: RemoteCommandHandler =
    handleChangePlaybackPositionCommandDefault
  public lazy var handleNextTrackCommand: RemoteCommandHandler = handleNextTrackCommandDefault
  public lazy var handlePreviousTrackCommand: RemoteCommandHandler =
    handlePreviousTrackCommandDefault
  public lazy var handleLikeCommand: RemoteCommandHandler = handleLikeCommandDefault
  public lazy var handleDislikeCommand: RemoteCommandHandler = handleDislikeCommandDefault
  public lazy var handleBookmarkCommand: RemoteCommandHandler = handleBookmarkCommandDefault

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
      callbacks?.remoteSeek(position: event.positionTime)
      return MPRemoteCommandHandlerStatus.success
    }
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

  private func getRemoteCommandHandlerStatus(forError error: Error)
    -> MPRemoteCommandHandlerStatus
  {
    return error is TrackPlayerError.QueueError
      ? MPRemoteCommandHandlerStatus.noSuchContent
      : MPRemoteCommandHandlerStatus.commandFailed
  }
}
