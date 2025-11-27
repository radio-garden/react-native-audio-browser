package com.audiobrowser.player

import android.os.Bundle
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.R
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import com.margelo.nitro.audiobrowser.ButtonCapability
import com.margelo.nitro.audiobrowser.Capability
import com.margelo.nitro.audiobrowser.NotificationButtonLayout
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
    const val CUSTOM_ACTION_FAVORITE = "FAVORITE"
  }

  /** Current player commands configuration for external controllers */
  var playerCommands: MediaPlayer.Commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
    private set

  /** Current player commands configuration for notification controller */
  var notificationPlayerCommands: MediaPlayer.Commands =
    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
    private set

  /** Current session commands configuration for external controllers */
  lateinit var sessionCommands: SessionCommands
    private set

  /** Current session commands configuration for notification */
  lateinit var notificationSessionCommands: SessionCommands
    private set

  /** Current custom layout configuration for external controllers */
  lateinit var customLayout: List<CommandButton>
    private set

  /** Current custom layout configuration for notification */
  lateinit var notificationCustomLayout: List<CommandButton>
    private set

  /** Stored state for rebuilding layout */
  private var currentCapabilities: List<Capability> = emptyList()
  private var currentNotificationButtons: NotificationButtonLayout? = null
  private var currentSearchAvailable: Boolean = false
  private var currentFavorited: Boolean? = null

  init {
    // Initialize with defaults matching PlayerUpdateOptions
    val defaultCapabilities =
      listOf(
        Capability.PLAY,
        Capability.PAUSE,
        Capability.SKIP_TO_NEXT,
        Capability.SKIP_TO_PREVIOUS,
        Capability.SEEK_TO,
      )

    playerCommands = buildPlayerCommands(defaultCapabilities)
    notificationPlayerCommands = buildPlayerCommands(defaultCapabilities)

    val (extSessionCommands, extCustomLayout) =
      buildSessionCommandsAndLayout(defaultCapabilities, searchAvailable = false, favorited = null)
    sessionCommands = extSessionCommands
    customLayout = extCustomLayout

    notificationSessionCommands = extSessionCommands
    notificationCustomLayout = extCustomLayout
  }

  /**
   * Updates MediaSession configuration and applies changes immediately
   *
   * @param mediaSession The MediaSession to configure
   * @param capabilities Global capabilities that enable commands for ALL MediaSession controllers
   *   (Bluetooth, Android Auto, lock screen, notification, etc.).
   * @param notificationButtons Slot-based button layout for notifications. When null, button layout
   *   is derived from capabilities using smart defaults.
   * @param searchAvailable Whether search functionality is configured and available
   *
   * Manager initializes with defaults: all global capabilities, limited notification capabilities.
   */
  fun updateMediaSession(
    mediaSession: MediaSession,
    capabilities: List<Capability>,
    notificationButtons: NotificationButtonLayout?,
    searchAvailable: Boolean,
    favorited: Boolean? = null,
  ) {
    // Store state for future rebuilds
    currentCapabilities = capabilities
    currentNotificationButtons = notificationButtons
    currentSearchAvailable = searchAvailable
    currentFavorited = favorited

    // Build commands for external controllers (global capabilities)
    playerCommands = buildPlayerCommands(capabilities)
    val (extSessionCommands, extCustomLayout) =
      buildSessionCommandsAndLayout(capabilities, searchAvailable, favorited)
    sessionCommands = extSessionCommands
    customLayout = extCustomLayout

    // Build notification button preferences with explicit slots
    val (notifSessionCommands, notifButtonPrefs) =
      buildNotificationButtonPreferences(
        capabilities,
        notificationButtons,
        searchAvailable,
        favorited,
      )
    notificationSessionCommands = notifSessionCommands
    notificationCustomLayout = notifButtonPrefs

    // Derive notification player commands from the buttons that will be shown
    notificationPlayerCommands = buildNotificationPlayerCommands(capabilities, notificationButtons)

    // Apply media button preferences to notification controller
    mediaSession.mediaNotificationControllerInfo?.let { controllerInfo ->
      mediaSession.setMediaButtonPreferences(controllerInfo, notifButtonPrefs)
      mediaSession.setAvailableCommands(
        controllerInfo,
        notificationSessionCommands,
        notificationPlayerCommands,
      )
    }

    // Broadcast updated layout to all external controllers (Android Auto, etc.)
    mediaSession.setCustomLayout(customLayout)
  }

  /**
   * Updates the favorite button state and reapplies the button preferences. Call this when the
   * current track changes or when favorite state is toggled.
   */
  fun updateFavoriteState(mediaSession: MediaSession, favorited: Boolean?) {
    Timber.Forest.d(
      "updateFavoriteState called: currentFavorited=$currentFavorited, newFavorited=$favorited"
    )
    if (currentFavorited == favorited) {
      Timber.Forest.d("Favorite state unchanged, skipping update")
      return
    }

    currentFavorited = favorited

    // Rebuild external controller layout with new favorite state
    val (extSessionCommands, extCustomLayout) =
      buildSessionCommandsAndLayout(currentCapabilities, currentSearchAvailable, favorited)
    sessionCommands = extSessionCommands
    customLayout = extCustomLayout
    Timber.Forest.d("Built external customLayout with ${customLayout.size} buttons")

    // Rebuild notification button preferences with new favorite state
    val (notifSessionCommands, notifButtonPrefs) =
      buildNotificationButtonPreferences(
        currentCapabilities,
        currentNotificationButtons,
        currentSearchAvailable,
        favorited,
      )
    notificationSessionCommands = notifSessionCommands
    notificationCustomLayout = notifButtonPrefs

    // Apply updated button preferences to notification controller
    mediaSession.mediaNotificationControllerInfo?.let { controllerInfo ->
      Timber.Forest.d("Updating notification controller button preferences")
      mediaSession.setMediaButtonPreferences(controllerInfo, notificationCustomLayout)
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
      CUSTOM_ACTION_FAVORITE -> {
        Timber.Forest.d("Favorite command received")
        // Return true to indicate this was handled - the actual callback
        // is triggered in MediaSessionCallback.onCustomCommand
        true
      }
      else -> {
        Timber.Forest.w("Received unexpected custom command: ${command.customAction}")
        false
      }
    }
  }

  private fun buildPlayerCommands(capabilities: List<Capability>): MediaPlayer.Commands {
    val playerCommandsBuilder = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()

    // Commands to remove - start with always-disabled commands
    val disabledCommands =
      mutableSetOf<@MediaPlayer.Command Int>(
        // Always filter out direct media item commands to avoid dual-command confusion
        // This forces MediaSession to only use the "smart" commands we can control via capabilities
        MediaPlayer.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        MediaPlayer.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
      )

    // Only disable jump commands if capabilities are not present
    if (!capabilities.contains(Capability.JUMP_FORWARD)) {
      disabledCommands.add(MediaPlayer.COMMAND_SEEK_FORWARD)
    }
    if (!capabilities.contains(Capability.JUMP_BACKWARD)) {
      disabledCommands.add(MediaPlayer.COMMAND_SEEK_BACK)
    }

    // Check each capability and add commands to remove if not enabled
    val hasPlayPause = capabilities.any { it == Capability.PLAY || it == Capability.PAUSE }
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

    return playerCommandsBuilder.build()
  }

  private fun buildSessionCommandsAndLayout(
    capabilities: List<Capability>,
    searchAvailable: Boolean,
    favorited: Boolean?,
  ): Pair<SessionCommands, List<CommandButton>> {
    val customLayoutButtons = mutableListOf<CommandButton>()
    val sessionCommandsBuilder =
      MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()

    // Remove search commands if search is not configured
    if (!searchAvailable) {
      sessionCommandsBuilder.remove(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
      sessionCommandsBuilder.remove(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT)
      Timber.Forest.d("Removed search commands - search not configured")
    }

    // Create custom command buttons for jump commands (required for notification visibility)
    if (capabilities.contains(Capability.JUMP_BACKWARD)) {
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

    if (capabilities.contains(Capability.JUMP_FORWARD)) {
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

    // Add favorite button when FAVORITE capability is enabled
    if (capabilities.contains(Capability.FAVORITE)) {
      val heartIcon =
        if (favorited == true) {
          CommandButton.ICON_HEART_FILLED
        } else {
          CommandButton.ICON_HEART_UNFILLED
        }
      val displayName = if (favorited == true) "Remove from favorites" else "Add to favorites"
      val favoriteCommand = SessionCommand(CUSTOM_ACTION_FAVORITE, Bundle())

      customLayoutButtons.add(
        CommandButton.Builder(heartIcon)
          .setDisplayName(displayName)
          .setSessionCommand(favoriteCommand)
          .build()
      )
      sessionCommandsBuilder.add(favoriteCommand)
      Timber.Forest.d("Added favorite button - favorited=$favorited")
    }

    return Pair(sessionCommandsBuilder.build(), customLayoutButtons)
  }

  /**
   * Builds notification button preferences with explicit slot assignments. If notificationButtons
   * is null, derives button layout from capabilities.
   */
  private fun buildNotificationButtonPreferences(
    capabilities: List<Capability>,
    notificationButtons: NotificationButtonLayout?,
    searchAvailable: Boolean,
    favorited: Boolean?,
  ): Pair<SessionCommands, List<CommandButton>> {
    val buttons = mutableListOf<CommandButton>()
    val sessionCommandsBuilder =
      MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()

    // Remove search commands if search is not configured
    if (!searchAvailable) {
      sessionCommandsBuilder.remove(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
      sessionCommandsBuilder.remove(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT)
    }

    // Helper to create a CommandButton for a ButtonCapability with specific slot
    fun createButton(capability: ButtonCapability, slot: Int): CommandButton? {
      return when (capability) {
        ButtonCapability.SKIP_TO_PREVIOUS -> {
          if (capabilities.contains(Capability.SKIP_TO_PREVIOUS)) {
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
              .setDisplayName("Previous")
              .setPlayerCommand(MediaPlayer.COMMAND_SEEK_TO_PREVIOUS)
              .setSlots(slot)
              .build()
          } else null
        }
        ButtonCapability.SKIP_TO_NEXT -> {
          if (capabilities.contains(Capability.SKIP_TO_NEXT)) {
            CommandButton.Builder(CommandButton.ICON_NEXT)
              .setDisplayName("Next")
              .setPlayerCommand(MediaPlayer.COMMAND_SEEK_TO_NEXT)
              .setSlots(slot)
              .build()
          } else null
        }
        ButtonCapability.JUMP_BACKWARD -> {
          if (capabilities.contains(Capability.JUMP_BACKWARD)) {
            val command = SessionCommand(CUSTOM_ACTION_JUMP_BACKWARD, Bundle())
            sessionCommandsBuilder.add(command)
            CommandButton.Builder()
              .setDisplayName("Jump Backward")
              .setSessionCommand(command)
              .setIconResId(R.drawable.media3_icon_skip_back)
              .setSlots(slot)
              .build()
          } else null
        }
        ButtonCapability.JUMP_FORWARD -> {
          if (capabilities.contains(Capability.JUMP_FORWARD)) {
            val command = SessionCommand(CUSTOM_ACTION_JUMP_FORWARD, Bundle())
            sessionCommandsBuilder.add(command)
            CommandButton.Builder()
              .setDisplayName("Jump Forward")
              .setSessionCommand(command)
              .setIconResId(R.drawable.media3_icon_skip_forward)
              .setSlots(slot)
              .build()
          } else null
        }
        ButtonCapability.FAVORITE -> {
          if (capabilities.contains(Capability.FAVORITE)) {
            val heartIcon =
              if (favorited == true) CommandButton.ICON_HEART_FILLED
              else CommandButton.ICON_HEART_UNFILLED
            val displayName = if (favorited == true) "Remove from favorites" else "Add to favorites"
            val command = SessionCommand(CUSTOM_ACTION_FAVORITE, Bundle())
            sessionCommandsBuilder.add(command)
            CommandButton.Builder(heartIcon)
              .setDisplayName(displayName)
              .setSessionCommand(command)
              .setSlots(slot)
              .build()
          } else null
        }
      }
    }

    if (notificationButtons != null) {
      // Use explicit slot configuration
      notificationButtons.back?.let { cap ->
        createButton(cap, CommandButton.SLOT_BACK)?.let { buttons.add(it) }
      }
      notificationButtons.forward?.let { cap ->
        createButton(cap, CommandButton.SLOT_FORWARD)?.let { buttons.add(it) }
      }
      notificationButtons.backSecondary?.let { cap ->
        createButton(cap, CommandButton.SLOT_BACK_SECONDARY)?.let { buttons.add(it) }
      }
      notificationButtons.forwardSecondary?.let { cap ->
        createButton(cap, CommandButton.SLOT_FORWARD_SECONDARY)?.let { buttons.add(it) }
      }
      notificationButtons.overflow?.forEach { cap ->
        createButton(cap, CommandButton.SLOT_OVERFLOW)?.let { buttons.add(it) }
      }
    } else {
      // Derive from capabilities with default slot mapping
      val hasSkipPrevious = capabilities.contains(Capability.SKIP_TO_PREVIOUS)
      val hasSkipNext = capabilities.contains(Capability.SKIP_TO_NEXT)

      // Back slot: skip-to-previous, or jump-backward if no skip
      if (hasSkipPrevious) {
        createButton(ButtonCapability.SKIP_TO_PREVIOUS, CommandButton.SLOT_BACK)?.let {
          buttons.add(it)
        }
      } else if (capabilities.contains(Capability.JUMP_BACKWARD)) {
        createButton(ButtonCapability.JUMP_BACKWARD, CommandButton.SLOT_BACK)?.let {
          buttons.add(it)
        }
      }

      // Forward slot: skip-to-next, or jump-forward if no skip
      if (hasSkipNext) {
        createButton(ButtonCapability.SKIP_TO_NEXT, CommandButton.SLOT_FORWARD)?.let {
          buttons.add(it)
        }
      } else if (capabilities.contains(Capability.JUMP_FORWARD)) {
        createButton(ButtonCapability.JUMP_FORWARD, CommandButton.SLOT_FORWARD)?.let {
          buttons.add(it)
        }
      }

      // Secondary slots for jump buttons if skip buttons are present
      if (hasSkipPrevious && capabilities.contains(Capability.JUMP_BACKWARD)) {
        createButton(ButtonCapability.JUMP_BACKWARD, CommandButton.SLOT_BACK_SECONDARY)?.let {
          buttons.add(it)
        }
      }
      if (hasSkipNext && capabilities.contains(Capability.JUMP_FORWARD)) {
        createButton(ButtonCapability.JUMP_FORWARD, CommandButton.SLOT_FORWARD_SECONDARY)?.let {
          buttons.add(it)
        }
      }

      // Overflow: favorite
      if (capabilities.contains(Capability.FAVORITE)) {
        createButton(ButtonCapability.FAVORITE, CommandButton.SLOT_OVERFLOW)?.let {
          buttons.add(it)
        }
      }
    }

    Timber.Forest.d("Built notification button preferences: ${buttons.map { it.displayName }}")
    return Pair(sessionCommandsBuilder.build(), buttons)
  }

  /**
   * Builds player commands for the notification controller based on the button layout. This enables
   * the commands needed for the buttons that will be shown.
   */
  private fun buildNotificationPlayerCommands(
    capabilities: List<Capability>,
    notificationButtons: NotificationButtonLayout?,
  ): MediaPlayer.Commands {
    val builder = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()

    // Always remove direct media item commands to avoid dual-command confusion
    builder.remove(MediaPlayer.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    builder.remove(MediaPlayer.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)

    // Determine which buttons will be shown
    val showSkipPrevious: Boolean
    val showSkipNext: Boolean
    val showJumpBackward: Boolean
    val showJumpForward: Boolean

    if (notificationButtons != null) {
      showSkipPrevious =
        notificationButtons.back == ButtonCapability.SKIP_TO_PREVIOUS ||
          notificationButtons.backSecondary == ButtonCapability.SKIP_TO_PREVIOUS
      showSkipNext =
        notificationButtons.forward == ButtonCapability.SKIP_TO_NEXT ||
          notificationButtons.forwardSecondary == ButtonCapability.SKIP_TO_NEXT
      showJumpBackward =
        notificationButtons.back == ButtonCapability.JUMP_BACKWARD ||
          notificationButtons.backSecondary == ButtonCapability.JUMP_BACKWARD
      showJumpForward =
        notificationButtons.forward == ButtonCapability.JUMP_FORWARD ||
          notificationButtons.forwardSecondary == ButtonCapability.JUMP_FORWARD
    } else {
      // Default derivation from capabilities
      val hasSkipPrevious = capabilities.contains(Capability.SKIP_TO_PREVIOUS)
      val hasSkipNext = capabilities.contains(Capability.SKIP_TO_NEXT)
      showSkipPrevious = hasSkipPrevious
      showSkipNext = hasSkipNext
      showJumpBackward = capabilities.contains(Capability.JUMP_BACKWARD)
      showJumpForward = capabilities.contains(Capability.JUMP_FORWARD)
    }

    // Enable/disable commands based on what buttons are shown
    if (!showSkipPrevious || !capabilities.contains(Capability.SKIP_TO_PREVIOUS)) {
      builder.remove(MediaPlayer.COMMAND_SEEK_TO_PREVIOUS)
    }
    if (!showSkipNext || !capabilities.contains(Capability.SKIP_TO_NEXT)) {
      builder.remove(MediaPlayer.COMMAND_SEEK_TO_NEXT)
    }
    if (!showJumpBackward || !capabilities.contains(Capability.JUMP_BACKWARD)) {
      builder.remove(MediaPlayer.COMMAND_SEEK_BACK)
    }
    if (!showJumpForward || !capabilities.contains(Capability.JUMP_FORWARD)) {
      builder.remove(MediaPlayer.COMMAND_SEEK_FORWARD)
    }

    // Other commands based on global capabilities
    val hasPlayPause = capabilities.any { it == Capability.PLAY || it == Capability.PAUSE }
    if (!hasPlayPause) {
      builder.remove(MediaPlayer.COMMAND_PLAY_PAUSE)
    }
    if (!capabilities.contains(Capability.STOP)) {
      builder.remove(MediaPlayer.COMMAND_STOP)
    }
    if (!capabilities.contains(Capability.SEEK_TO)) {
      builder.remove(MediaPlayer.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    }

    return builder.build()
  }
}
