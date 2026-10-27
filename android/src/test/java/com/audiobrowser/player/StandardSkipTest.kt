package com.audiobrowser.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.margelo.nitro.audiobrowser.PlayerCapabilities
import com.margelo.nitro.audiobrowser.RemoteButton
import com.margelo.nitro.audiobrowser.RemoteButtonLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Media3 turns a button in the back or forward slot into a custom action and drops the matching
 * standard skip action from the platform PlaybackState; with the slot empty, the standard action
 * stays. Robolectric's MediaController cannot read that state, so these check the buttons and the
 * player commands behind the standard actions.
 */
@RunWith(RobolectricTestRunner::class)
class StandardSkipTest {

  private val app = RuntimeEnvironment.getApplication()
  private val exoPlayer = ExoPlayer.Builder(app).build()
  private val session =
    MediaSession.Builder(
        app,
        object : ForwardingPlayer(exoPlayer) {
          override fun getAvailableCommands() =
            MediaPlayer.Commands.Builder().addAllCommands().build()

          override fun isCommandAvailable(command: Int) = true
        },
      )
      .build()
  private val manager = MediaSessionCommandManager(automotive = false)

  @After
  fun release() {
    session.release()
    exoPlayer.release()
  }

  private fun capabilities(
    skipToNext: Boolean? = null,
    skipToPrevious: Boolean? = null,
    jump: Boolean = false,
    favorite: Boolean = true,
  ) =
    PlayerCapabilities(
      play = null,
      pause = null,
      stop = null,
      seekTo = null,
      skipToNext = skipToNext,
      skipToPrevious = skipToPrevious,
      jumpForward = jump,
      jumpBackward = jump,
      favorite = favorite,
      shuffleMode = null,
      repeatMode = null,
      playbackRate = null,
    )

  private fun buttons(
    capabilities: PlayerCapabilities,
    layout: RemoteButtonLayout? = null,
  ): List<Pair<String, Int>> {
    manager.updateMediaSession(session, capabilities, layout, searchAvailable = false)
    return manager.buildConnectionResult(session).mediaButtonPreferences!!.map {
      it.displayName.toString() to it.slots[0]
    }
  }

  private fun commands(capabilities: PlayerCapabilities): MediaPlayer.Commands {
    manager.updateMediaSession(session, capabilities, null, searchAvailable = false)
    return manager.buildConnectionResult(session).availablePlayerCommands
  }

  @Test
  fun `Previous and Next in their own slots are left to the standard skip actions`() {
    assertEquals(listOf("Add to favorites" to CommandButton.SLOT_OVERFLOW), buttons(capabilities()))
  }

  @Test
  fun `Android Automotive OS leaves the favorite to its own rating control`() {
    val automotive = MediaSessionCommandManager(automotive = true)
    automotive.updateMediaSession(session, capabilities(), null, searchAvailable = false)
    assertEquals(
      emptyList<CommandButton>(),
      automotive.buildConnectionResult(session).mediaButtonPreferences,
    )
  }

  @Test
  fun `skip placed anywhere else stays a button, and jump keeps the primary slots`() {
    val layout =
      RemoteButtonLayout(
        back = RemoteButton.JUMP_BACKWARD,
        forward = RemoteButton.JUMP_FORWARD,
        overflow = arrayOf(RemoteButton.SKIP_TO_NEXT),
      )
    assertEquals(
      listOf(
        "Jump Backward" to CommandButton.SLOT_BACK,
        "Jump Forward" to CommandButton.SLOT_FORWARD,
        "Next" to CommandButton.SLOT_OVERFLOW,
      ),
      buttons(capabilities(jump = true), layout),
    )
  }

  @Test
  fun `an enabled skip grants both of its commands`() {
    val commands = commands(capabilities())
    assertTrue(commands.contains(MediaPlayer.COMMAND_SEEK_TO_NEXT))
    assertTrue(commands.contains(MediaPlayer.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertTrue(commands.contains(MediaPlayer.COMMAND_SEEK_TO_PREVIOUS))
    assertTrue(commands.contains(MediaPlayer.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
  }

  @Test
  fun `a disabled skip removes both of its commands, and only those`() {
    val commands = commands(capabilities(skipToNext = false))
    assertFalse(commands.contains(MediaPlayer.COMMAND_SEEK_TO_NEXT))
    assertFalse(commands.contains(MediaPlayer.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertTrue(commands.contains(MediaPlayer.COMMAND_SEEK_TO_PREVIOUS))
    assertTrue(commands.contains(MediaPlayer.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
  }

  private fun acceptsRatings(capabilities: PlayerCapabilities): Boolean {
    manager.updateMediaSession(session, capabilities, null, searchAvailable = false)
    return manager
      .buildConnectionResult(session)
      .availableSessionCommands
      .contains(SessionCommand.COMMAND_CODE_SESSION_SET_RATING)
  }

  @Test
  fun `a rating, which sets the favorite, is accepted only while favoriting is on`() {
    assertTrue(acceptsRatings(capabilities(favorite = true)))
    assertFalse(acceptsRatings(capabilities(favorite = false)))
  }
}
