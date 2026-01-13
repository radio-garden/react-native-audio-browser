package com.audiobrowser.player

import android.os.Bundle
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.R
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import com.margelo.nitro.audiobrowser.NotificationButton
import com.margelo.nitro.audiobrowser.NotificationButtonLayout
import com.margelo.nitro.audiobrowser.PlayerCapabilities
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
  private var currentCapabilities: PlayerCapabilities = PlayerCapabilities(
    play = null, pause = null, stop = null, seekTo = null,
    skipToNext = null, skipToPrevious = null,
    jumpForward = null, jumpBackward = null,
    favorite = null,
    shuffleMode = null, repeatMode = null, playbackRate = null
  )
  private var currentNotificationButtons: NotificationButtonLayout? = null
  private var currentSearchAvailable: Boolean = false
  private var currentFavorited: Boolean? = null

  init {
    // Initialize with defaults matching PlayerUpdateOptions (all capabilities enabled)
    val defaultCapabilities = currentCapabilities

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
   *   (Bluetooth, Android Auto, lock screen, notification, etc.). All capabilities are enabled
   *   by default - only false values disable them.
   * @param notificationButtons Slot-based button layout for notifications. When null, button layout
   *   is derived from capabilities using smart defaults.
   * @param searchAvailable Whether search functionality is configured and available
   *
   * Manager initializes with defaults: all global capabilities, limited notification capabilities.
   */
  fun updateMediaSession(
    mediaSession: MediaSession,
    capabilities: PlayerCapabilities,
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

  private fun buildPlayerCommands(capabilities: PlayerCapabilities): MediaPlayer.Commands {
    val playerCommandsBuilder = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()

    // Commands to remove - start with always-disabled commands
    val disabledCommands =
      mutableSetOf<@MediaPlayer.Command Int>(
        // Always filter out direct media item commands to avoid dual-command confusion
        // This forces MediaSession to only use the "smart" commands we can control via capabilities
        MediaPlayer.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        MediaPlayer.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
      )

    // Only disable jump commands if capabilities are explicitly disabled (false)
    if (capabilities.jumpForward == false) {
      disabledCommands.add(MediaPlayer.COMMAND_SEEK_FORWARD)
    }
    if (capabilities.jumpBackward == false) {
      disabledCommands.add(MediaPlayer.COMMAND_SEEK_BACK)
    }

    // Check each capability and add commands to remove if explicitly disabled
    // Both play and pause must be disabled to remove PLAY_PAUSE command
    if (capabilities.play == false && capabilities.pause == false) {
      disabledCommands.add(MediaPlayer.COMMAND_PLAY_PAUSE)
    }

    if (capabilities.stop == false) {
      disabledCommands.add(MediaPlayer.COMMAND_STOP)
    }

    if (capabilities.seekTo == false) {
      disabledCommands.add(MediaPlayer.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    }

    if (capabilities.skipToNext == false) {
      disabledCommands.add(MediaPlayer.COMMAND_SEEK_TO_NEXT)
    }

    if (capabilities.skipToPrevious == false) {
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
    capabilities: PlayerCapabilities,
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
    // All capabilities enabled by default - only false disables
    if (capabilities.jumpBackward != false) {
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

    if (capabilities.jumpForward != false) {
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

    // Add favorite button when FAVORITE capability is not disabled
    if (capabilities.favorite != false) {
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
    capabilities: PlayerCapabilities,
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

    // Helper to create a CommandButton for a NotificationButton with specific slot
    // Only creates button if the corresponding capability is not disabled
    fun createButton(button: NotificationButton, slot: Int): CommandButton? {
      return when (button) {
        NotificationButton.SKIP_TO_PREVIOUS -> {
          if (capabilities.skipToPrevious != false) {
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
              .setDisplayName("Previous")
              .setPlayerCommand(MediaPlayer.COMMAND_SEEK_TO_PREVIOUS)
              .setSlots(slot)
              .build()
          } else null
        }
        NotificationButton.SKIP_TO_NEXT -> {
          if (capabilities.skipToNext != false) {
            CommandButton.Builder(CommandButton.ICON_NEXT)
              .setDisplayName("Next")
              .setPlayerCommand(MediaPlayer.COMMAND_SEEK_TO_NEXT)
              .setSlots(slot)
              .build()
          } else null
        }
        NotificationButton.JUMP_BACKWARD -> {
          if (capabilities.jumpBackward != false) {
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
        NotificationButton.JUMP_FORWARD -> {
          if (capabilities.jumpForward != false) {
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
        NotificationButton.FAVORITE -> {
          if (capabilities.favorite != false) {
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
      notificationButtons.back?.let { btn ->
        createButton(btn, CommandButton.SLOT_BACK)?.let { buttons.add(it) }
      }
      notificationButtons.forward?.let { btn ->
        createButton(btn, CommandButton.SLOT_FORWARD)?.let { buttons.add(it) }
      }
      notificationButtons.backSecondary?.let { btn ->
        createButton(btn, CommandButton.SLOT_BACK_SECONDARY)?.let { buttons.add(it) }
      }
      notificationButtons.forwardSecondary?.let { btn ->
        createButton(btn, CommandButton.SLOT_FORWARD_SECONDARY)?.let { buttons.add(it) }
      }
      notificationButtons.overflow?.forEach { btn ->
        createButton(btn, CommandButton.SLOT_OVERFLOW)?.let { buttons.add(it) }
      }
    } else {
      // Derive from capabilities with default slot mapping
      // All capabilities enabled by default - only false disables
      val hasSkipPrevious = capabilities.skipToPrevious != false
      val hasSkipNext = capabilities.skipToNext != false

      // Back slot: skip-to-previous, or jump-backward if no skip
      if (hasSkipPrevious) {
        createButton(NotificationButton.SKIP_TO_PREVIOUS, CommandButton.SLOT_BACK)?.let {
          buttons.add(it)
        }
      } else if (capabilities.jumpBackward != false) {
        createButton(NotificationButton.JUMP_BACKWARD, CommandButton.SLOT_BACK)?.let {
          buttons.add(it)
        }
      }

      // Forward slot: skip-to-next, or jump-forward if no skip
      if (hasSkipNext) {
        createButton(NotificationButton.SKIP_TO_NEXT, CommandButton.SLOT_FORWARD)?.let {
          buttons.add(it)
        }
      } else if (capabilities.jumpForward != false) {
        createButton(NotificationButton.JUMP_FORWARD, CommandButton.SLOT_FORWARD)?.let {
          buttons.add(it)
        }
      }

      // Secondary slots for jump buttons if skip buttons are present
      if (hasSkipPrevious && capabilities.jumpBackward != false) {
        createButton(NotificationButton.JUMP_BACKWARD, CommandButton.SLOT_BACK_SECONDARY)?.let {
          buttons.add(it)
        }
      }
      if (hasSkipNext && capabilities.jumpForward != false) {
        createButton(NotificationButton.JUMP_FORWARD, CommandButton.SLOT_FORWARD_SECONDARY)?.let {
          buttons.add(it)
        }
      }

      // Overflow: favorite
      if (capabilities.favorite != false) {
        createButton(NotificationButton.FAVORITE, CommandButton.SLOT_OVERFLOW)?.let {
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
    capabilities: PlayerCapabilities,
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
        notificationButtons.back == NotificationButton.SKIP_TO_PREVIOUS ||
          notificationButtons.backSecondary == NotificationButton.SKIP_TO_PREVIOUS
      showSkipNext =
        notificationButtons.forward == NotificationButton.SKIP_TO_NEXT ||
          notificationButtons.forwardSecondary == NotificationButton.SKIP_TO_NEXT
      showJumpBackward =
        notificationButtons.back == NotificationButton.JUMP_BACKWARD ||
          notificationButtons.backSecondary == NotificationButton.JUMP_BACKWARD
      showJumpForward =
        notificationButtons.forward == NotificationButton.JUMP_FORWARD ||
          notificationButtons.forwardSecondary == NotificationButton.JUMP_FORWARD
    } else {
      // Default derivation from capabilities
      // All capabilities enabled by default - only false disables
      showSkipPrevious = capabilities.skipToPrevious != false
      showSkipNext = capabilities.skipToNext != false
      showJumpBackward = capabilities.jumpBackward != false
      showJumpForward = capabilities.jumpForward != false
    }

    // Enable/disable commands based on what buttons are shown and capabilities
    if (!showSkipPrevious || capabilities.skipToPrevious == false) {
      builder.remove(MediaPlayer.COMMAND_SEEK_TO_PREVIOUS)
    }
    if (!showSkipNext || capabilities.skipToNext == false) {
      builder.remove(MediaPlayer.COMMAND_SEEK_TO_NEXT)
    }
    if (!showJumpBackward || capabilities.jumpBackward == false) {
      builder.remove(MediaPlayer.COMMAND_SEEK_BACK)
    }
    if (!showJumpForward || capabilities.jumpForward == false) {
      builder.remove(MediaPlayer.COMMAND_SEEK_FORWARD)
    }

    // Other commands based on global capabilities
    // Both play and pause must be disabled to remove PLAY_PAUSE
    if (capabilities.play == false && capabilities.pause == false) {
      builder.remove(MediaPlayer.COMMAND_PLAY_PAUSE)
    }
    if (capabilities.stop == false) {
      builder.remove(MediaPlayer.COMMAND_STOP)
    }
    if (capabilities.seekTo == false) {
      builder.remove(MediaPlayer.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    }

    return builder.build()
  }
}
