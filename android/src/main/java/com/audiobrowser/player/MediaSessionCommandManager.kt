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
  private var currentCapabilities: PlayerCapabilities =
    PlayerCapabilities(
      play = null,
      pause = null,
      stop = null,
      seekTo = null,
      skipToNext = null,
      skipToPrevious = null,
      jumpForward = null,
      jumpBackward = null,
      favorite = null,
      shuffleMode = null,
      repeatMode = null,
      playbackRate = null,
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
   *   (Bluetooth, Android Auto, lock screen, notification, etc.). All capabilities are enabled by
   *   default - only false values disable them.
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

    // One slot derivation feeds both the notification layout and its player
    // commands, so the buttons shown and the commands enabled cannot drift.
    val slots = deriveNotificationSlots(capabilities, notificationButtons)
    val (notifSessionCommands, notifButtonPrefs) =
      buildNotificationButtonPreferences(slots, searchAvailable, favorited)
    notificationSessionCommands = notifSessionCommands
    notificationCustomLayout = notifButtonPrefs
    notificationPlayerCommands = buildNotificationPlayerCommands(capabilities, slots)

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
        deriveNotificationSlots(currentCapabilities, currentNotificationButtons),
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

  /**
   * The Media3 player command behind each Capability-gated control (FAVORITE is a session command).
   */
  private val controlPlayerCommands: Map<Control, @MediaPlayer.Command Int> =
    mapOf(
      Control.PLAY_PAUSE to MediaPlayer.COMMAND_PLAY_PAUSE,
      Control.STOP to MediaPlayer.COMMAND_STOP,
      Control.SEEK_TO to MediaPlayer.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
      Control.SKIP_TO_NEXT to MediaPlayer.COMMAND_SEEK_TO_NEXT,
      Control.SKIP_TO_PREVIOUS to MediaPlayer.COMMAND_SEEK_TO_PREVIOUS,
      Control.JUMP_FORWARD to MediaPlayer.COMMAND_SEEK_FORWARD,
      Control.JUMP_BACKWARD to MediaPlayer.COMMAND_SEEK_BACK,
    )

  private fun buildPlayerCommands(capabilities: PlayerCapabilities): MediaPlayer.Commands {
    val playerCommandsBuilder = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()

    // Always filter out direct media item commands to avoid dual-command confusion.
    // This forces MediaSession to only use the "smart" commands we can control via capabilities.
    playerCommandsBuilder.remove(MediaPlayer.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    playerCommandsBuilder.remove(MediaPlayer.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)

    controlPlayerCommands.forEach { (control, command) ->
      if (!capabilities.isEnabled(control)) {
        playerCommandsBuilder.remove(command)
        Timber.Forest.d("Removed command: $command ($control disabled)")
      }
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

    // Custom command buttons: jump commands (required for notification visibility) and the
    // favorite heart — slot-less here; the notification path assigns slots to the same buttons.
    val externalButtons =
      listOf(
          NotificationButton.JUMP_BACKWARD to Control.JUMP_BACKWARD,
          NotificationButton.JUMP_FORWARD to Control.JUMP_FORWARD,
          NotificationButton.FAVORITE to Control.FAVORITE,
        )
        .filter { (_, control) -> capabilities.isEnabled(control) }
    for ((button, _) in externalButtons) {
      val (builder, command) = buttonFor(button, favorited)
      customLayoutButtons.add(builder.build())
      command?.let { sessionCommandsBuilder.add(it) }
    }

    return Pair(sessionCommandsBuilder.build(), customLayoutButtons)
  }

  /**
   * The CommandButton (and its custom SessionCommand, when the button is session-command-backed)
   * for a control — the single definition of display name, icon, and action, shared by the
   * external-controller layout and the notification layout. The caller registers the returned
   * command on its SessionCommands and assigns slots.
   */
  private fun buttonFor(
    button: NotificationButton,
    favorited: Boolean?,
  ): Pair<CommandButton.Builder, SessionCommand?> =
    when (button) {
      NotificationButton.SKIP_TO_PREVIOUS ->
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
          .setDisplayName("Previous")
          .setPlayerCommand(MediaPlayer.COMMAND_SEEK_TO_PREVIOUS) to null
      NotificationButton.SKIP_TO_NEXT ->
        CommandButton.Builder(CommandButton.ICON_NEXT)
          .setDisplayName("Next")
          .setPlayerCommand(MediaPlayer.COMMAND_SEEK_TO_NEXT) to null
      NotificationButton.JUMP_BACKWARD -> {
        val command = SessionCommand(CUSTOM_ACTION_JUMP_BACKWARD, Bundle())
        CommandButton.Builder()
          .setDisplayName("Jump Backward")
          .setSessionCommand(command)
          .setIconResId(R.drawable.media3_icon_skip_back) to command
      }
      NotificationButton.JUMP_FORWARD -> {
        val command = SessionCommand(CUSTOM_ACTION_JUMP_FORWARD, Bundle())
        CommandButton.Builder()
          .setDisplayName("Jump Forward")
          .setSessionCommand(command)
          .setIconResId(R.drawable.media3_icon_skip_forward) to command
      }
      NotificationButton.FAVORITE -> {
        val heartIcon =
          if (favorited == true) CommandButton.ICON_HEART_FILLED
          else CommandButton.ICON_HEART_UNFILLED
        val displayName = if (favorited == true) "Remove from favorites" else "Add to favorites"
        val command = SessionCommand(CUSTOM_ACTION_FAVORITE, Bundle())
        CommandButton.Builder(heartIcon).setDisplayName(displayName).setSessionCommand(command) to
          command
      }
    }

  /**
   * Assembles the notification CommandButtons (+ their session commands) for an already-derived
   * slot layout (see [deriveNotificationSlots] — the slots are pre-filtered by Capability).
   */
  private fun buildNotificationButtonPreferences(
    slots: List<SlottedButton>,
    searchAvailable: Boolean,
    favorited: Boolean?,
  ): Pair<SessionCommands, List<CommandButton>> {
    val sessionCommandsBuilder =
      MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()

    // Remove search commands if search is not configured
    if (!searchAvailable) {
      sessionCommandsBuilder.remove(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
      sessionCommandsBuilder.remove(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT)
    }

    fun media3Slot(slot: NotificationSlot): Int =
      when (slot) {
        NotificationSlot.BACK -> CommandButton.SLOT_BACK
        NotificationSlot.FORWARD -> CommandButton.SLOT_FORWARD
        NotificationSlot.BACK_SECONDARY -> CommandButton.SLOT_BACK_SECONDARY
        NotificationSlot.FORWARD_SECONDARY -> CommandButton.SLOT_FORWARD_SECONDARY
        NotificationSlot.OVERFLOW -> CommandButton.SLOT_OVERFLOW
      }

    val buttons =
      slots.map { (button, slot) ->
        val (builder, command) = buttonFor(button, favorited)
        command?.let { sessionCommandsBuilder.add(it) }
        builder.setSlots(media3Slot(slot)).build()
      }

    Timber.Forest.d("Built notification button preferences: ${buttons.map { it.displayName }}")
    return Pair(sessionCommandsBuilder.build(), buttons)
  }

  /**
   * Builds player commands for the notification controller from the SAME slot derivation that
   * builds its buttons (so a shown button always has its command enabled — including buttons placed
   * in overflow, which the previous hand-rolled derivation missed). Slots are pre-filtered by
   * Capability; the button-less controls (play/pause, stop, seek) gate on the global capabilities.
   */
  private fun buildNotificationPlayerCommands(
    capabilities: PlayerCapabilities,
    slots: List<SlottedButton>,
  ): MediaPlayer.Commands {
    val builder = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()

    // Always remove direct media item commands to avoid dual-command confusion
    builder.remove(MediaPlayer.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    builder.remove(MediaPlayer.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)

    val shown = slots.mapTo(mutableSetOf()) { it.button }
    if (NotificationButton.SKIP_TO_PREVIOUS !in shown) {
      builder.remove(MediaPlayer.COMMAND_SEEK_TO_PREVIOUS)
    }
    if (NotificationButton.SKIP_TO_NEXT !in shown) {
      builder.remove(MediaPlayer.COMMAND_SEEK_TO_NEXT)
    }
    if (NotificationButton.JUMP_BACKWARD !in shown) {
      builder.remove(MediaPlayer.COMMAND_SEEK_BACK)
    }
    if (NotificationButton.JUMP_FORWARD !in shown) {
      builder.remove(MediaPlayer.COMMAND_SEEK_FORWARD)
    }

    if (!capabilities.isEnabled(Control.PLAY_PAUSE)) {
      builder.remove(MediaPlayer.COMMAND_PLAY_PAUSE)
    }
    if (!capabilities.isEnabled(Control.STOP)) {
      builder.remove(MediaPlayer.COMMAND_STOP)
    }
    if (!capabilities.isEnabled(Control.SEEK_TO)) {
      builder.remove(MediaPlayer.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    }

    return builder.build()
  }
}
