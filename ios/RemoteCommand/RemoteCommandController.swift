import Foundation
import MediaPlayer
import os.log

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

/// Enables the remote commands (lock screen, Control Center, CarPlay, headset) and routes
/// each event to `RemoteCommandCallbacks`.
@MainActor
final class RemoteCommandController {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "RemoteCommandController")
  private var center: MPRemoteCommandCenter

  weak var callbacks: RemoteCommandCallbacks?

  private var enabledCommands: [RemoteCommand] = []

  /// Whether the queue has a next / previous track; read whenever those commands are enabled.
  var skipAvailability: @MainActor () -> (canNext: Bool, canPrevious: Bool) = { (true, true) }

  /// The shuffle/repeat display state, kept so it survives a switch to a new command center,
  /// which starts with both off.
  private var shuffleEnabled = false
  private var repeatMode: RepeatMode = .off

  init(
    remoteCommandCenter: MPRemoteCommandCenter = MPRemoteCommandCenter.shared(),
    callbacks: RemoteCommandCallbacks? = nil,
  ) {
    center = remoteCommandCenter
    self.callbacks = callbacks
  }

  /// Moves the enabled commands and their state to `newCenter`: the linked
  /// `MPNowPlayingSession` has its own command center, which replaces the shared one.
  func switchCommandCenter(_ newCenter: MPRemoteCommandCenter) {
    guard newCenter !== center else { return }
    let commands = enabledCommands
    disableAll()
    center = newCenter
    enable(commands: commands)
    center.changeShuffleModeCommand.currentShuffleType = shuffleEnabled.mpShuffleType
    center.changeRepeatModeCommand.currentRepeatType = repeatMode.mpRepeatType
    logger.info("Switched command center, re-enabled \(commands.count) commands")
  }

  /// Enables exactly `commands`, disabling any enabled command they no longer include.
  func enable(commands: [RemoteCommand]) {
    RemoteCommand.commandsToDisable(enabled: enabledCommands, replacedBy: commands).forEach(disable)
    enabledCommands = commands
    commands.forEach(enable)
  }

  func disableAll() {
    enabledCommands.forEach(disable)
    enabledCommands = []
  }

  func updateRepeatMode(_ mode: RepeatMode) {
    repeatMode = mode
    center.changeRepeatModeCommand.currentRepeatType = mode.mpRepeatType
  }

  func updateShuffleMode(_ enabled: Bool) {
    shuffleEnabled = enabled
    center.changeShuffleModeCommand.currentShuffleType = enabled.mpShuffleType
  }

  /// Greys out next/previous when `skipAvailability` says the queue has nowhere to skip.
  func refreshSkipAvailability() {
    let (canNext, canPrevious) = skipAvailability()
    if enabledCommands.contains(.next) { center.nextTrackCommand.isEnabled = canNext }
    if enabledCommands.contains(.previous) { center.previousTrackCommand.isEnabled = canPrevious }
  }

  // MARK: - Private

  private func mpCommand(for command: RemoteCommand) -> MPRemoteCommand {
    switch command {
    case .play: center.playCommand
    case .pause: center.pauseCommand
    case .stop: center.stopCommand
    case .togglePlayPause: center.togglePlayPauseCommand
    case .next: center.nextTrackCommand
    case .previous: center.previousTrackCommand
    case .changePlaybackPosition: center.changePlaybackPositionCommand
    case .skipForward: center.skipForwardCommand
    case .skipBackward: center.skipBackwardCommand
    case .changeRepeatMode: center.changeRepeatModeCommand
    case .changeShuffleMode: center.changeShuffleModeCommand
    case .changePlaybackRate: center.changePlaybackRateCommand
    }
  }

  private func enable(_ command: RemoteCommand) {
    switch command {
    case let .skipForward(intervals): center.skipForwardCommand.preferredIntervals = intervals
    case let .skipBackward(intervals): center.skipBackwardCommand.preferredIntervals = intervals
    case let .changePlaybackRate(rates): center.changePlaybackRateCommand.supportedPlaybackRates = rates
    default: break
    }
    let mpCommand = mpCommand(for: command)
    mpCommand.removeTarget(nil)
    mpCommand.addTarget { [weak self] event in
      self?.handle(event, for: command) ?? .commandFailed
    }
    mpCommand.isEnabled = switch command {
    case .next: skipAvailability().canNext
    case .previous: skipAvailability().canPrevious
    default: true
    }
  }

  private func disable(_ command: RemoteCommand) {
    let mpCommand = mpCommand(for: command)
    mpCommand.isEnabled = false
    mpCommand.removeTarget(nil)
  }

  private func handle(_ event: MPRemoteCommandEvent, for command: RemoteCommand) -> MPRemoteCommandHandlerStatus {
    switch command {
    case .play: callbacks?.remotePlay()
    case .pause: callbacks?.remotePause()
    case .stop: callbacks?.remoteStop()
    case .togglePlayPause: callbacks?.remotePlayPause()
    case .next: callbacks?.remoteNext()
    case .previous: callbacks?.remotePrevious()
    case .skipForward, .skipBackward:
      guard let interval = (event.command as? MPSkipIntervalCommand)?.preferredIntervals.first else {
        return .commandFailed
      }
      if case .skipForward = command {
        callbacks?.remoteJumpForward(interval: interval.doubleValue)
      } else {
        callbacks?.remoteJumpBackward(interval: interval.doubleValue)
      }
    case .changePlaybackPosition:
      guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
      callbacks?.remoteSeek(position: event.positionTime)
    case .changeRepeatMode:
      guard let event = event as? MPChangeRepeatModeCommandEvent else { return .commandFailed }
      callbacks?.remoteChangeRepeatMode(mode: RepeatMode(from: event.repeatType))
    case .changeShuffleMode:
      guard let event = event as? MPChangeShuffleModeCommandEvent else { return .commandFailed }
      callbacks?.remoteChangeShuffleMode(enabled: event.shuffleType != .off)
    case .changePlaybackRate:
      guard let event = event as? MPChangePlaybackRateCommandEvent else { return .commandFailed }
      callbacks?.remoteChangePlaybackRate(rate: event.playbackRate)
    }
    return .success
  }
}
