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
import timber.log.Timber

/**
 * Whether a surface draws this button itself from the standard PlaybackState: Previous in BACK and
 * Next in FORWARD everywhere, and the favorite on Android Automotive OS (its rating control).
 */
private fun SlottedButton.isDrawnBySurface(automotive: Boolean): Boolean =
  (button == RemoteButton.SKIP_TO_PREVIOUS && slot == ButtonSlot.BACK) ||
    (button == RemoteButton.SKIP_TO_NEXT && slot == ButtonSlot.FORWARD) ||
    (automotive && button == RemoteButton.FAVORITE)

/**
 * The slot, then OVERFLOW. Media3 reads `slots` as a preference order and places a button in the
 * first that exists, is free and allows it; every surface's flattening
 * (`CommandButton.getCustomLayoutFromMediaButtonPreferences`, `DefaultMediaNotificationProvider`)
 * keeps only a button that won BACK or FORWARD or lists OVERFLOW, so without the fallback a button
 * that loses its slot disappears instead of demoting.
 */
internal fun media3SlotsFor(slot: ButtonSlot): IntArray =
  when (slot) {
    ButtonSlot.BACK -> intArrayOf(CommandButton.SLOT_BACK, CommandButton.SLOT_OVERFLOW)
    ButtonSlot.FORWARD -> intArrayOf(CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW)
    ButtonSlot.OVERFLOW -> intArrayOf(CommandButton.SLOT_OVERFLOW)
  }

/**
 * Maps player capabilities to the session's available commands and its one button layout.
 *
 * Capabilities decide what works; the layout only decides where buttons sit, so a control left out
 * of the layout still works from a Bluetooth remote or headset. The layout is published
 * session-wide via `setMediaButtonPreferences`, which Media3 turns into the notification and the
 * legacy PlaybackState that Android Auto and the Android 13+ media controls read. A per-controller
 * layout would reach neither: Android Auto connects as a legacy controller.
 */
class MediaSessionCommandManager(private val automotive: Boolean) {

  companion object {
    private const val CUSTOM_ACTION_JUMP_BACKWARD = "JUMP_BACKWARD"
    private const val CUSTOM_ACTION_JUMP_FORWARD = "JUMP_FORWARD"
    const val CUSTOM_ACTION_FAVORITE = "FAVORITE"

    // Seconds; matches PlayerUpdateOptions' default.
    private const val DEFAULT_JUMP_INTERVAL = 15.0
  }

  /**
   * Media3 has numbered icons only for 5, 10, 15 and 30 seconds. The match is exact: a numbered
   * icon promises how far the button seeks, so 14.6 gets the plain arrow, not "15". Android Auto
   * draws from this icon constant, not from an icon resource set with `setIconResId`.
   */
  private fun jumpForwardIcon(seconds: Double): Int =
    when (seconds) {
      5.0 -> CommandButton.ICON_SKIP_FORWARD_5
      10.0 -> CommandButton.ICON_SKIP_FORWARD_10
      15.0 -> CommandButton.ICON_SKIP_FORWARD_15
      30.0 -> CommandButton.ICON_SKIP_FORWARD_30
      else -> CommandButton.ICON_SKIP_FORWARD
    }

  private fun jumpBackwardIcon(seconds: Double): Int =
    when (seconds) {
      5.0 -> CommandButton.ICON_SKIP_BACK_5
      10.0 -> CommandButton.ICON_SKIP_BACK_10
      15.0 -> CommandButton.ICON_SKIP_BACK_15
      30.0 -> CommandButton.ICON_SKIP_BACK_30
      else -> CommandButton.ICON_SKIP_BACK
    }

  var playerCommands: MediaPlayer.Commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
    private set

  lateinit var sessionCommands: SessionCommands
    private set

  lateinit var buttonPreferences: List<CommandButton>
    private set

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
  private var currentLive: Boolean = false
  private var currentForwardJumpInterval: Double = DEFAULT_JUMP_INTERVAL
  private var currentBackwardJumpInterval: Double = DEFAULT_JUMP_INTERVAL

  /**
   * FAVORITE is a session command, so it is absent here. Skip maps to two commands: Media3
   * advertises the platform skip action for either, and its notification uses the media-item one,
   * so a disabled skip must remove both.
   */
  private val capabilityPlayerCommands: Map<Capability, List<@MediaPlayer.Command Int>> =
    mapOf(
      Capability.PLAY_PAUSE to listOf(MediaPlayer.COMMAND_PLAY_PAUSE),
      Capability.STOP to listOf(MediaPlayer.COMMAND_STOP),
      Capability.SEEK_TO to listOf(MediaPlayer.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
      Capability.SKIP_TO_NEXT to
        listOf(MediaPlayer.COMMAND_SEEK_TO_NEXT, MediaPlayer.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM),
      Capability.SKIP_TO_PREVIOUS to
        listOf(
          MediaPlayer.COMMAND_SEEK_TO_PREVIOUS,
          MediaPlayer.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        ),
      Capability.JUMP_FORWARD to listOf(MediaPlayer.COMMAND_SEEK_FORWARD),
      Capability.JUMP_BACKWARD to listOf(MediaPlayer.COMMAND_SEEK_BACK),
    )

  // capabilityPlayerCommands must stay above `init`: properties initialize in textual order, and
  // `init` reads it through buildPlayerCommands.
  init {
    val defaultCapabilities = currentCapabilities

    playerCommands = buildPlayerCommands(defaultCapabilities, live = false)
    sessionCommands = buildSessionCommands(defaultCapabilities, searchAvailable = false)
    buttonPreferences =
      buildButtonPreferences(
        deriveButtonSlots(defaultCapabilities, layout = null),
        favorited = null,
      )
  }

  /**
   * Rebuilds and publishes commands and layout. A null [remoteButtonLayout] derives the layout from
   * [capabilities]. The favorite and live states set by [updateFavoriteState] and [updateLiveState]
   * carry over.
   */
  fun updateMediaSession(
    mediaSession: MediaSession,
    capabilities: PlayerCapabilities,
    remoteButtonLayout: RemoteButtonLayout?,
    searchAvailable: Boolean,
    forwardJumpInterval: Double = DEFAULT_JUMP_INTERVAL,
    backwardJumpInterval: Double = DEFAULT_JUMP_INTERVAL,
  ) {
    currentCapabilities = capabilities
    currentRemoteButtonLayout = remoteButtonLayout
    currentForwardJumpInterval = forwardJumpInterval
    currentBackwardJumpInterval = backwardJumpInterval

    playerCommands = buildPlayerCommands(capabilities, currentLive)
    sessionCommands = buildSessionCommands(capabilities, searchAvailable)
    buttonPreferences =
      buildButtonPreferences(deriveButtonSlots(capabilities, remoteButtonLayout), currentFavorited)

    // Commands first: Media3 disables a button whose command is not yet available.
    publishAvailableCommands(mediaSession)
    publishButtonPreferences(mediaSession)
  }

  /**
   * Withholds seeking within the current item while it is live, whatever `seekTo` says: a
   * controller offered that command draws a seek bar a live stream cannot fill. Call whenever the
   * current track may have changed.
   */
  fun updateLiveState(mediaSession: MediaSession, live: Boolean) {
    if (currentLive == live) return
    currentLive = live
    playerCommands = buildPlayerCommands(currentCapabilities, live)
    publishAvailableCommands(mediaSession)
  }

  // [buildConnectionResult] only reaches controllers that connect later; an Android Auto connection
  // lasts until the car shuts down.
  private fun publishAvailableCommands(mediaSession: MediaSession) {
    mediaSession.connectedControllers.forEach {
      mediaSession.setAvailableCommands(it, sessionCommands, playerCommands)
    }
  }

  /**
   * Both calls are needed. Only the session-wide one reaches Android Auto, a legacy controller, but
   * it re-broadcasts only when the slot-reservation flags change, never the PlaybackState carrying
   * the custom actions. The notification-controller overload calls
   * `updateLegacySessionPlaybackState`; without it a reordered layout stays unseen until some
   * playback event republishes the state.
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

  /** Call when the current track changes or its favorite state is toggled. */
  fun updateFavoriteState(mediaSession: MediaSession, favorited: Boolean?) {
    Timber.Forest.d(
      "updateFavoriteState called: currentFavorited=$currentFavorited, newFavorited=$favorited"
    )
    if (currentFavorited == favorited) {
      Timber.Forest.d("Favorite state unchanged, skipping update")
      return
    }

    currentFavorited = favorited

    buttonPreferences =
      buildButtonPreferences(
        deriveButtonSlots(currentCapabilities, currentRemoteButtonLayout),
        favorited,
      )

    publishButtonPreferences(mediaSession)
  }

  fun buildConnectionResult(session: MediaSession): MediaSession.ConnectionResult {
    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
      .setMediaButtonPreferences(buttonPreferences)
      .setAvailableSessionCommands(sessionCommands)
      .setAvailablePlayerCommands(playerCommands)
      .build()
  }

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
        // Unreached: MediaSessionCallback.onCustomCommand handles the favorite itself.
        Timber.Forest.d("Favorite command received")
        true
      }
      else -> {
        Timber.Forest.w("Received unexpected custom command: ${command.customAction}")
        false
      }
    }
  }

  private fun buildPlayerCommands(
    capabilities: PlayerCapabilities,
    live: Boolean,
  ): MediaPlayer.Commands {
    val playerCommandsBuilder = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()

    capabilityPlayerCommands.forEach { (capability, commands) ->
      if (!capabilities.isEnabled(capability)) {
        commands.forEach { playerCommandsBuilder.remove(it) }
        Timber.Forest.d("Removed commands: $commands ($capability disabled)")
      }
    }

    if (live) {
      playerCommandsBuilder.remove(MediaPlayer.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    }

    return playerCommandsBuilder.build()
  }

  // Registers the command of every capability-allowed button, placed or not: Media3 disables a
  // button whose command is missing.
  private fun buildSessionCommands(
    capabilities: PlayerCapabilities,
    searchAvailable: Boolean,
  ): SessionCommands {
    val sessionCommandsBuilder =
      MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()

    if (!searchAvailable) {
      sessionCommandsBuilder.remove(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
      sessionCommandsBuilder.remove(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT)
      Timber.Forest.d("Removed search commands - search not configured")
    }

    // Media3 always advertises the platform rating action, which sets the favorite.
    if (!capabilities.isEnabled(Capability.FAVORITE)) {
      sessionCommandsBuilder.remove(SessionCommand.COMMAND_CODE_SESSION_SET_RATING)
    }

    RemoteButton.entries
      .filter { capabilities.allows(it) }
      .mapNotNull { customCommandFor(it) }
      .forEach { sessionCommandsBuilder.add(it) }

    return sessionCommandsBuilder.build()
  }

  // The one source for both the command a button carries and the registry in buildSessionCommands,
  // so a published button cannot lack its command.
  private fun customCommandFor(button: RemoteButton): SessionCommand? =
    when (button) {
      RemoteButton.JUMP_BACKWARD -> SessionCommand(CUSTOM_ACTION_JUMP_BACKWARD, Bundle())
      RemoteButton.JUMP_FORWARD -> SessionCommand(CUSTOM_ACTION_JUMP_FORWARD, Bundle())
      RemoteButton.FAVORITE -> SessionCommand(CUSTOM_ACTION_FAVORITE, Bundle())
      RemoteButton.SKIP_TO_PREVIOUS,
      RemoteButton.SKIP_TO_NEXT -> null
    }

  // This and customCommandFor list every button instead of `else`, so a new button fails the build
  // until it has an action.
  private fun playerCommandFor(button: RemoteButton): Int? =
    when (button) {
      RemoteButton.SKIP_TO_PREVIOUS -> MediaPlayer.COMMAND_SEEK_TO_PREVIOUS
      RemoteButton.SKIP_TO_NEXT -> MediaPlayer.COMMAND_SEEK_TO_NEXT
      RemoteButton.JUMP_BACKWARD,
      RemoteButton.JUMP_FORWARD,
      RemoteButton.FAVORITE -> null
    }

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
   * Leaves out the buttons a surface draws itself, so Media3 keeps the standard skip actions and
   * reserves their slots. Sent as buttons they arrive as custom actions, which Android Automotive
   * OS's car media app places by its own rules rather than beside play/pause.
   */
  private fun buildButtonPreferences(
    slots: List<SlottedButton>,
    favorited: Boolean?,
  ): List<CommandButton> =
    slots
      .filterNot { it.isDrawnBySurface(automotive) }
      .map { (button, slot) ->
        buttonFor(button, favorited).setSlots(*media3SlotsFor(slot)).build()
      }
}
