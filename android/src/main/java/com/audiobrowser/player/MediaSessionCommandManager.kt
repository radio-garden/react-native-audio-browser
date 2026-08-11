package com.audiobrowser.player

import android.os.Bundle
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import com.margelo.nitro.audiobrowser.PlayerCapabilities
import com.margelo.nitro.audiobrowser.RemoteButton
import com.margelo.nitro.audiobrowser.RemoteButtonLayout
import kotlin.math.roundToInt
import timber.log.Timber

/**
 * A slot's Media3 preference list: the requested slot, then OVERFLOW as a fallback.
 *
 * `slots` is a preference order, not a coordinate — Media3 places a button in the first slot in the
 * list "that exists, isn't already occupied and that allows this type of button". Naming OVERFLOW
 * second means a button whose primary position is unavailable on some surface demotes instead of
 * disappearing, because every flattening
 * (`CommandButton.getCustomLayoutFromMediaButtonPreferences`, run by both `MediaSessionLegacyStub`
 * for Android Auto and the Android 13+ controls, and `DefaultMediaNotificationProvider` for the
 * notification) keeps a button only if it won BACK, won FORWARD, or *contains* OVERFLOW.
 */
internal fun media3SlotsFor(slot: ButtonSlot): IntArray =
  when (slot) {
    ButtonSlot.BACK -> intArrayOf(CommandButton.SLOT_BACK, CommandButton.SLOT_OVERFLOW)
    ButtonSlot.FORWARD -> intArrayOf(CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW)
    ButtonSlot.OVERFLOW -> intArrayOf(CommandButton.SLOT_OVERFLOW)
  }

/**
 * MediaSession manager that handles command configuration and execution.
 *
 * Responsibilities:
 * - Maps player capabilities to MediaSession commands
 * - Builds the single, slot-annotated button layout published to every surface
 * - Updates MediaSession configuration and applies changes immediately
 * - Builds connection results for new MediaSession controllers
 * - Handles execution of custom MediaSession commands (jump actions)
 *
 * Capabilities decide what the player can do; the button layout decides where those buttons sit.
 * Placement never revokes a capability — a control left out of the layout still works from a
 * Bluetooth remote or headset.
 *
 * There is ONE layout, published session-wide via `setMediaButtonPreferences`. Media3 fans it out:
 * `MediaNotificationManager` renders it as the MediaStyle notification (Android 12 and below), and
 * `MediaSessionLegacyStub` converts it into the platform PlaybackState plus the slot-reservation
 * extras that Android Auto and the Android 13+ system media controls read. Publishing
 * per-controller instead would reach neither, since Android Auto connects as a legacy controller.
 */
class MediaSessionCommandManager {

  companion object {
    private const val CUSTOM_ACTION_JUMP_BACKWARD = "JUMP_BACKWARD"
    private const val CUSTOM_ACTION_JUMP_FORWARD = "JUMP_FORWARD"
    const val CUSTOM_ACTION_FAVORITE = "FAVORITE"

    /** Matches PlayerUpdateOptions.forwardJumpInterval/backwardJumpInterval */
    private const val DEFAULT_JUMP_INTERVAL = 15.0
  }

  /**
   * Media3 ships dedicated icons for the common jump intervals; anything else falls back to the
   * generic skip icon. The icon constant — not the drawable — is what a controller that renders its
   * own UI reads, and it is carried through the legacy conversion Android Auto consumes. Building a
   * button with only `setIconResId` leaves the constant at ICON_UNDEFINED, so the car has nothing
   * to draw.
   */
  private fun jumpForwardIcon(seconds: Double): Int =
    when (seconds.roundToInt()) {
      5 -> CommandButton.ICON_SKIP_FORWARD_5
      10 -> CommandButton.ICON_SKIP_FORWARD_10
      15 -> CommandButton.ICON_SKIP_FORWARD_15
      30 -> CommandButton.ICON_SKIP_FORWARD_30
      else -> CommandButton.ICON_SKIP_FORWARD
    }

  private fun jumpBackwardIcon(seconds: Double): Int =
    when (seconds.roundToInt()) {
      5 -> CommandButton.ICON_SKIP_BACK_5
      10 -> CommandButton.ICON_SKIP_BACK_10
      15 -> CommandButton.ICON_SKIP_BACK_15
      30 -> CommandButton.ICON_SKIP_BACK_30
      else -> CommandButton.ICON_SKIP_BACK
    }

  /** Player commands available to every controller, derived from capabilities alone */
  var playerCommands: MediaPlayer.Commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
    private set

  /** Session commands available to every controller, derived from capabilities alone */
  lateinit var sessionCommands: SessionCommands
    private set

  /** The single slot-annotated button layout, published to every surface */
  lateinit var buttonPreferences: List<CommandButton>
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
  private var currentRemoteButtonLayout: RemoteButtonLayout? = null
  private var currentFavorited: Boolean? = null
  private var currentForwardJumpInterval: Double = DEFAULT_JUMP_INTERVAL
  private var currentBackwardJumpInterval: Double = DEFAULT_JUMP_INTERVAL

  /** The Media3 player command behind each Capability (FAVORITE is a session command). */
  private val capabilityPlayerCommands: Map<Capability, @MediaPlayer.Command Int> =
    mapOf(
      Capability.PLAY_PAUSE to MediaPlayer.COMMAND_PLAY_PAUSE,
      Capability.STOP to MediaPlayer.COMMAND_STOP,
      Capability.SEEK_TO to MediaPlayer.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
      Capability.SKIP_TO_NEXT to MediaPlayer.COMMAND_SEEK_TO_NEXT,
      Capability.SKIP_TO_PREVIOUS to MediaPlayer.COMMAND_SEEK_TO_PREVIOUS,
      Capability.JUMP_FORWARD to MediaPlayer.COMMAND_SEEK_FORWARD,
      Capability.JUMP_BACKWARD to MediaPlayer.COMMAND_SEEK_BACK,
    )

  // Declared above `init` deliberately: `init` calls `buildPlayerCommands`, which reads
  // `capabilityPlayerCommands`. Kotlin initializes properties in textual order, so this val must
  // precede the init block or it would still be null when the constructor runs (NPE).
  init {
    // Initialize with defaults matching PlayerUpdateOptions (all capabilities enabled)
    val defaultCapabilities = currentCapabilities

    playerCommands = buildPlayerCommands(defaultCapabilities)
    sessionCommands = buildSessionCommands(defaultCapabilities, searchAvailable = false)
    buttonPreferences =
      buildButtonPreferences(
        deriveButtonSlots(defaultCapabilities, layout = null),
        favorited = null,
      )
  }

  /**
   * Updates MediaSession configuration and applies changes immediately
   *
   * @param mediaSession The MediaSession to configure
   * @param capabilities Global capabilities that enable commands for ALL MediaSession controllers
   *   (Bluetooth, Android Auto, lock screen, notification, etc.). All capabilities are enabled by
   *   default - only false values disable them.
   * @param remoteButtonLayout Button layout, published to every surface — the notification, Android
   *   Auto, and the Android 13+ system media controls. When null the layout is derived from
   *   capabilities using smart defaults.
   * @param searchAvailable Whether search functionality is configured and available
   * @param forwardJumpInterval Seconds per forward jump; selects the button's icon constant
   * @param backwardJumpInterval Seconds per backward jump; selects the button's icon constant
   *
   * Manager initializes with defaults: all capabilities enabled, default placement.
   */
  fun updateMediaSession(
    mediaSession: MediaSession,
    capabilities: PlayerCapabilities,
    remoteButtonLayout: RemoteButtonLayout?,
    searchAvailable: Boolean,
    forwardJumpInterval: Double = DEFAULT_JUMP_INTERVAL,
    backwardJumpInterval: Double = DEFAULT_JUMP_INTERVAL,
  ) {
    // Store state for future rebuilds. The favorite state is deliberately NOT reset here: options
    // and favorites change independently, and rebuilding the layout as un-favorited would blank the
    // heart on every updateOptions() call until the next track change happened to refresh it.
    currentCapabilities = capabilities
    currentRemoteButtonLayout = remoteButtonLayout
    currentForwardJumpInterval = forwardJumpInterval
    currentBackwardJumpInterval = backwardJumpInterval

    // Commands gate on capabilities only — placement never revokes one, so a control left out of
    // the layout still works from a Bluetooth remote or headset.
    playerCommands = buildPlayerCommands(capabilities)
    sessionCommands = buildSessionCommands(capabilities, searchAvailable)
    buttonPreferences =
      buildButtonPreferences(deriveButtonSlots(capabilities, remoteButtonLayout), currentFavorited)

    // Push the rebuilt commands to already-connected controllers — buildConnectionResult only
    // serves controllers that connect later, and an Android Auto session lives until the car shuts
    // down. Granted before the layout broadcast so a newly-added button arrives with its command
    // already available; Media3 disables buttons whose backing command is missing.
    mediaSession.connectedControllers.forEach {
      mediaSession.setAvailableCommands(it, sessionCommands, playerCommands)
    }

    publishButtonPreferences(mediaSession)
  }

  /**
   * Publishes the layout. Media3 fans it out to the MediaStyle notification (Android 12 and below)
   * and to the platform PlaybackState plus slot-reservation extras (Android Auto and the Android
   * 13+ system media controls).
   *
   * Both calls are required. The session-wide call is the only one that reaches Android Auto, which
   * connects as a legacy controller rather than a Media3 one. But it stops at
   * `setPlatformMediaButtonPreferences`, which recomputes the legacy custom layout and only
   * re-broadcasts when the reservation flags themselves change — it never refreshes the
   * PlaybackState carrying the custom actions. Only the notification-controller overload calls
   * `updateLegacySessionPlaybackState`, so without it a reordering that leaves the flags untouched
   * sits invisible until some unrelated playback event republishes the state.
   */
  private fun publishButtonPreferences(mediaSession: MediaSession) {
    Timber.Forest.d(
      "Publishing ${buttonPreferences.size} button preferences: " +
        "${buttonPreferences.map { it.displayName }}"
    )
    mediaSession.setMediaButtonPreferences(buttonPreferences)

    mediaSession.mediaNotificationControllerInfo?.let { controllerInfo ->
      mediaSession.setMediaButtonPreferences(controllerInfo, buttonPreferences)
    }
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

    // Only the heart's icon and label change, so the commands stay as they are.
    buttonPreferences =
      buildButtonPreferences(
        deriveButtonSlots(currentCapabilities, currentRemoteButtonLayout),
        favorited,
      )

    // Republish so already-connected surfaces (Android Auto especially) flip the heart — without
    // this it stays stale for the life of the connection.
    publishButtonPreferences(mediaSession)
  }

  /**
   * Builds a MediaSession ConnectionResult with current command configuration
   *
   * @param session The MediaSession to build the result for
   * @return Configured ConnectionResult with current commands and layout
   */
  fun buildConnectionResult(session: MediaSession): MediaSession.ConnectionResult {
    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
      .setMediaButtonPreferences(buttonPreferences)
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

    // Always filter out direct media item commands to avoid dual-command confusion.
    // This forces MediaSession to only use the "smart" commands we can control via capabilities.
    playerCommandsBuilder.remove(MediaPlayer.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    playerCommandsBuilder.remove(MediaPlayer.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)

    capabilityPlayerCommands.forEach { (capability, command) ->
      if (!capabilities.isEnabled(capability)) {
        playerCommandsBuilder.remove(command)
        Timber.Forest.d("Removed command: $command ($capability disabled)")
      }
    }

    return playerCommandsBuilder.build()
  }

  /**
   * Session commands for every controller. Registered for each capability-allowed custom button
   * whether or not the layout places it — placement governs where a button appears, not whether its
   * command exists. Media3 disables a button whose backing command is missing, so every button the
   * layout can produce must have its command here.
   */
  private fun buildSessionCommands(
    capabilities: PlayerCapabilities,
    searchAvailable: Boolean,
  ): SessionCommands {
    val sessionCommandsBuilder =
      MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()

    // Remove search commands if search is not configured
    if (!searchAvailable) {
      sessionCommandsBuilder.remove(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
      sessionCommandsBuilder.remove(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT)
      Timber.Forest.d("Removed search commands - search not configured")
    }

    RemoteButton.entries
      .filter { capabilities.allows(it) }
      .mapNotNull { customCommandFor(it) }
      .forEach { sessionCommandsBuilder.add(it) }

    return sessionCommandsBuilder.build()
  }

  /**
   * The custom command behind a session-command-backed button, or null for the player-command ones
   * (skip). Single source for both the command a button carries and the SessionCommands registry,
   * so a published button can never be missing the command that makes it work — Media3 silently
   * disables buttons whose backing command is unavailable.
   */
  private fun customCommandFor(button: RemoteButton): SessionCommand? =
    when (button) {
      RemoteButton.JUMP_BACKWARD -> SessionCommand(CUSTOM_ACTION_JUMP_BACKWARD, Bundle())
      RemoteButton.JUMP_FORWARD -> SessionCommand(CUSTOM_ACTION_JUMP_FORWARD, Bundle())
      RemoteButton.FAVORITE -> SessionCommand(CUSTOM_ACTION_FAVORITE, Bundle())
      RemoteButton.SKIP_TO_PREVIOUS,
      RemoteButton.SKIP_TO_NEXT -> null
    }

  /**
   * The Media3 player command behind a player-command-backed button; null for the custom ones. Both
   * this and [customCommandFor] enumerate every button rather than using `else`, so adding one
   * fails the build until it has been given an action on both sides.
   */
  private fun playerCommandFor(button: RemoteButton): Int? =
    when (button) {
      RemoteButton.SKIP_TO_PREVIOUS -> MediaPlayer.COMMAND_SEEK_TO_PREVIOUS
      RemoteButton.SKIP_TO_NEXT -> MediaPlayer.COMMAND_SEEK_TO_NEXT
      RemoteButton.JUMP_BACKWARD,
      RemoteButton.JUMP_FORWARD,
      RemoteButton.FAVORITE -> null
    }

  /** The single definition of a control's icon and label, shared by every surface. */
  private fun buttonFor(button: RemoteButton, favorited: Boolean?): CommandButton.Builder {
    val builder =
      when (button) {
        RemoteButton.SKIP_TO_PREVIOUS ->
          CommandButton.Builder(CommandButton.ICON_PREVIOUS).setDisplayName("Previous")
        RemoteButton.SKIP_TO_NEXT ->
          CommandButton.Builder(CommandButton.ICON_NEXT).setDisplayName("Next")
        RemoteButton.JUMP_BACKWARD ->
          CommandButton.Builder(jumpBackwardIcon(currentBackwardJumpInterval))
            .setDisplayName("Jump Backward")
        RemoteButton.JUMP_FORWARD ->
          CommandButton.Builder(jumpForwardIcon(currentForwardJumpInterval))
            .setDisplayName("Jump Forward")
        RemoteButton.FAVORITE ->
          CommandButton.Builder(
              if (favorited == true) CommandButton.ICON_HEART_FILLED
              else CommandButton.ICON_HEART_UNFILLED
            )
            .setDisplayName(if (favorited == true) "Remove from favorites" else "Add to favorites")
      }

    return customCommandFor(button)?.let { builder.setSessionCommand(it) }
      ?: playerCommandFor(button)?.let { builder.setPlayerCommand(it) }
      ?: builder
  }

  /**
   * Assembles the CommandButtons for an already-derived slot layout (see [deriveButtonSlots] — the
   * slots are pre-filtered by Capability).
   *
   * Each button gets its requested slot followed by OVERFLOW as a fallback. `slots` is an ordered
   * preference list, and every surface caps how many buttons it renders — a head unit, the
   * collapsed notification, the Android 13+ media controls all truncate differently. Without a
   * fallback a button that loses its preferred slot disappears entirely instead of demoting.
   */
  private fun buildButtonPreferences(
    slots: List<SlottedButton>,
    favorited: Boolean?,
  ): List<CommandButton> =
    slots.map { (button, slot) ->
      buttonFor(button, favorited).setSlots(*media3SlotsFor(slot)).build()
    }
}
