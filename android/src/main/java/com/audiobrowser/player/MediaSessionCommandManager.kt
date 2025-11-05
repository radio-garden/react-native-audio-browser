package com.audiobrowser.player

import android.os.Bundle
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.R
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import com.margelo.nitro.audiobrowser.Capability
import timber.log.Timber

/**
 * MediaSession manager that handles command configuration and execution.
 *
 * Responsibilities:
 * - Maps player capabilities to MediaSession commands and notification layouts
 * - Updates MediaSession configuration and applies changes immediately
 * - Builds connection results for new MediaSession controllers
 * - Handles execution of custom MediaSession commands (jump actions)
 * - Maintains proper separation between global capabilities and notification-specific controls
 *
 * Initializes with sensible defaults: all global capabilities enabled, essential notification
 * controls only.
 */
class MediaSessionCommandManager {

  companion object {
    private const val CUSTOM_ACTION_JUMP_BACKWARD = "JUMP_BACKWARD"
    private const val CUSTOM_ACTION_JUMP_FORWARD = "JUMP_FORWARD"
  }

  /** Current player commands configuration */
  var playerCommands: MediaPlayer.Commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
    private set

  /** Current session commands configuration */
  lateinit var sessionCommands: SessionCommands
    private set

  /** Current custom layout configuration */
  lateinit var customLayout: List<CommandButton>
    private set

  init {
    // Initialize with defaults:
    // - Allow all capabilities globally (for full external controller support)
    // - Limit notification capabilities to essential controls
    val defaultNotificationCapabilities =
      listOf(
        Capability.PLAY,
        Capability.PAUSE,
        Capability.SKIP_TO_NEXT,
        Capability.SKIP_TO_PREVIOUS,
        Capability.SEEK_TO,
      )

    updatePlayerCommands(Capability.entries) // All capabilities for external controllers
    updateSessionCommandsAndLayout(defaultNotificationCapabilities)
  }

  /**
   * Updates MediaSession configuration and applies changes immediately
   *
   * @param mediaSession The MediaSession to configure
   * @param capabilities Global capabilities that enable commands for ALL MediaSession controllers
   *   (Bluetooth, Android Auto, lock screen, notification, etc.).
   * @param notificationCapabilities Capabilities that control which buttons appear in notifications
   *   only. When null, defaults to capabilities. Empty list disables all notification buttons.
   *
   * Manager initializes with defaults: all global capabilities, limited notification capabilities.
   */
  fun updateMediaSession(
      mediaSession: MediaSession,
      capabilities: List<Capability>,
      notificationCapabilities: List<Capability>?,
  ) {
    // Update internal configuration
    updatePlayerCommands(capabilities)
    val effectiveNotificationCapabilities = notificationCapabilities ?: capabilities
    updateSessionCommandsAndLayout(effectiveNotificationCapabilities)

    // Apply configuration to MediaSession notification controller
    mediaSession.mediaNotificationControllerInfo?.let { controllerInfo ->
      mediaSession.setCustomLayout(controllerInfo, customLayout)
      mediaSession.setAvailableCommands(controllerInfo, sessionCommands, playerCommands)
    }
  }

  /**
   * Builds a MediaSession ConnectionResult with current command configuration
   *
   * @param session The MediaSession to build the result for
   * @return Configured ConnectionResult with current commands and layout
   */
  fun buildConnectionResult(session: MediaSession): MediaSession.ConnectionResult {
    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
      .setCustomLayout(customLayout)
      .setAvailableSessionCommands(sessionCommands)
      .setAvailablePlayerCommands(playerCommands)
      .build()
  }

  /**
   * Handles custom MediaSession commands
   *
   * @param command The custom command to handle
   * @param player The AudioBrowser instance to execute commands on
   * @return true if command was handled, false otherwise
   */
  fun handleCustomCommand(command: SessionCommand, player: Player): Boolean {
    Timber.Forest.d("onCustomCommand: action=${command.customAction}")

    return when (command.customAction) {
      CUSTOM_ACTION_JUMP_BACKWARD -> {
        Timber.Forest.d("Executing jump backward command")
        player.forwardingPlayer.seekBack()
        true
      }
      CUSTOM_ACTION_JUMP_FORWARD -> {
        Timber.Forest.d("Executing jump forward command")
        player.forwardingPlayer.seekForward()
        true
      }
      else -> {
        Timber.Forest.w("Received unexpected custom command: ${command.customAction}")
        false
      }
    }
  }

  private fun updatePlayerCommands(capabilities: List<Capability>) {
    val playerCommandsBuilder = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()

    // Commands to remove - start with always-disabled commands
    val disabledCommands =
      mutableSetOf<@MediaPlayer.Command Int>(
        // Always filter out direct media item commands to avoid dual-command confusion
        // This forces MediaSession to only use the "smart" commands we can control via capabilities
        MediaPlayer.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        MediaPlayer.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
      )

    // Only disable jump commands if global capabilities are not present
    // This preserves them for external controllers (Bluetooth, Android Auto, etc.)
    if (!capabilities.contains(Capability.JUMP_FORWARD)) {
      disabledCommands.add(MediaPlayer.COMMAND_SEEK_FORWARD)
    }
    if (!capabilities.contains(Capability.JUMP_BACKWARD)) {
      disabledCommands.add(MediaPlayer.COMMAND_SEEK_BACK)
    }

    // Check each capability and add commands to remove if not enabled
    val hasPlayPause =
      capabilities.any { it == Capability.PLAY || it == Capability.PAUSE }
    if (!hasPlayPause) {
      disabledCommands.add(MediaPlayer.COMMAND_PLAY_PAUSE)
    }

    if (!capabilities.contains(Capability.STOP)) {
      disabledCommands.add(MediaPlayer.COMMAND_STOP)
    }

    if (!capabilities.contains(Capability.SEEK_TO)) {
      disabledCommands.add(MediaPlayer.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    }

    if (!capabilities.contains(Capability.SKIP_TO_NEXT)) {
      disabledCommands.add(MediaPlayer.COMMAND_SEEK_TO_NEXT)
    }

    if (!capabilities.contains(Capability.SKIP_TO_PREVIOUS)) {
      disabledCommands.add(MediaPlayer.COMMAND_SEEK_TO_PREVIOUS)
    }

    // Remove disabled commands from the builder
    disabledCommands.forEach { command ->
      playerCommandsBuilder.remove(command)
      Timber.Forest.d("Removed command: $command")
    }

    playerCommands = playerCommandsBuilder.build()
  }

  private fun updateSessionCommandsAndLayout(notificationCapabilities: List<Capability>) {
    val customLayoutButtons = mutableListOf<CommandButton>()
    val sessionCommandsBuilder =
      MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()

    // Create custom command buttons for jump commands (required for notification visibility)
    if (notificationCapabilities.contains(Capability.JUMP_BACKWARD)) {
      val jumpBackCommand = SessionCommand(CUSTOM_ACTION_JUMP_BACKWARD, Bundle())
      customLayoutButtons.add(
        CommandButton.Builder()
          .setDisplayName("Jump Backward")
          .setSessionCommand(jumpBackCommand)
          .setIconResId(R.drawable.media3_icon_skip_back)
          .build()
      )
      sessionCommandsBuilder.add(jumpBackCommand)
    }

    if (notificationCapabilities.contains(Capability.JUMP_FORWARD)) {
      val jumpForwardCommand = SessionCommand(CUSTOM_ACTION_JUMP_FORWARD, Bundle())
      customLayoutButtons.add(
        CommandButton.Builder()
          .setDisplayName("Jump Forward")
          .setSessionCommand(jumpForwardCommand)
          .setIconResId(R.drawable.media3_icon_skip_forward)
          .build()
      )
      sessionCommandsBuilder.add(jumpForwardCommand)
    }

    sessionCommands = sessionCommandsBuilder.build()
    customLayout = customLayoutButtons
  }
}