package com.audiobrowser.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import com.margelo.nitro.audiobrowser.PlayerCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Media3 turns a button in the back or forward slot into a custom action and drops the matching
 * standard skip action from the platform PlaybackState; with the slot empty, the standard action
 * stays. Robolectric's MediaController cannot read that state, so these check the buttons.
 */
@RunWith(RobolectricTestRunner::class)
class AutomotiveSkipTest {

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

  @After
  fun release() {
    session.release()
    exoPlayer.release()
  }

  private fun capabilities(favorite: Boolean? = null) =
    PlayerCapabilities(
      play = null,
      pause = null,
      stop = null,
      seekTo = null,
      skipToNext = null,
      skipToPrevious = null,
      jumpForward = false,
      jumpBackward = false,
      favorite = favorite,
      shuffleMode = null,
      repeatMode = null,
      playbackRate = null,
    )

  private fun buttonsFor(automotive: Boolean): List<Pair<String, Int>> =
    MediaSessionCommandManager(automotive)
      .apply {
        updateMediaSession(
          session,
          capabilities(favorite = true),
          remoteButtonLayout = null,
          searchAvailable = false,
        )
      }
      .buildConnectionResult(session)
      .mediaButtonPreferences!!
      .map { it.displayName.toString() to it.slots[0] }

  @Test
  fun `a phone places Previous and Next in the back and forward slots`() {
    assertEquals(
      listOf(
        "Previous" to CommandButton.SLOT_BACK,
        "Next" to CommandButton.SLOT_FORWARD,
        "Add to favorites" to CommandButton.SLOT_OVERFLOW,
      ),
      buttonsFor(automotive = false),
    )
  }

  @Test
  fun `Android Automotive OS leaves Previous and Next to the standard skip actions`() {
    assertEquals(
      listOf("Add to favorites" to CommandButton.SLOT_OVERFLOW),
      buttonsFor(automotive = true),
    )
  }
}
