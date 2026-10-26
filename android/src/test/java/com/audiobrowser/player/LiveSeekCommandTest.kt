package com.audiobrowser.player

import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.margelo.nitro.audiobrowser.PlayerCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

// Robolectric: a MediaSession needs the framework.
@RunWith(RobolectricTestRunner::class)
class LiveSeekCommandTest {

  private val app = RuntimeEnvironment.getApplication()
  private val exoPlayer = ExoPlayer.Builder(app).build()
  // A controller sees the session's grant intersected with the player's own commands, and an empty
  // player cannot seek, so this one offers every command to leave only the session's grant.
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
  private val manager = MediaSessionCommandManager()

  @After
  fun release() {
    session.release()
    exoPlayer.release()
  }

  private fun capabilities(seekTo: Boolean? = null) =
    PlayerCapabilities(
      play = null,
      pause = null,
      stop = null,
      seekTo = seekTo,
      skipToNext = null,
      skipToPrevious = null,
      jumpForward = null,
      jumpBackward = null,
      favorite = null,
      shuffleMode = null,
      repeatMode = null,
      playbackRate = null,
    )

  private fun update(capabilities: PlayerCapabilities) =
    manager.updateMediaSession(
      session,
      capabilities,
      remoteButtonLayout = null,
      searchAvailable = false,
    )

  private fun offersSeek(): Boolean =
    manager
      .buildConnectionResult(session)
      .availablePlayerCommands
      .contains(MediaPlayer.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)

  @Test
  fun `a live item cannot be seeked, though seekTo is enabled`() {
    update(capabilities(seekTo = true))
    manager.updateLiveState(session, live = true)
    assertFalse(offersSeek())
  }

  @Test
  fun `seeking returns when the next item is not live`() {
    update(capabilities())
    manager.updateLiveState(session, live = true)
    manager.updateLiveState(session, live = false)
    assertTrue(offersSeek())
  }

  @Test
  fun `a capability update while live keeps seeking withheld`() {
    manager.updateLiveState(session, live = true)
    update(capabilities(seekTo = true))
    assertFalse(offersSeek())
  }

  @Test
  fun `a disabled seekTo stays disabled for an item that is not live`() {
    update(capabilities(seekTo = false))
    manager.updateLiveState(session, live = true)
    manager.updateLiveState(session, live = false)
    assertFalse(offersSeek())
  }

  @Test
  fun `being live takes only seeking away`() {
    update(capabilities())
    val notLive = manager.buildConnectionResult(session).availablePlayerCommands
    manager.updateLiveState(session, live = true)
    val live = manager.buildConnectionResult(session).availablePlayerCommands
    assertEquals(
      notLive.buildUpon().remove(MediaPlayer.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).build(),
      live,
    )
  }

  @Test
  fun `a controller already connected loses and regains seeking`() {
    val future = MediaController.Builder(app, session.token).buildAsync()
    shadowOf(Looper.getMainLooper()).idle()
    val controller = future.get()
    fun controllerOffersSeek(): Boolean {
      shadowOf(Looper.getMainLooper()).idle()
      return controller.isCommandAvailable(MediaPlayer.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    }

    try {
      manager.updateLiveState(session, live = true)
      assertFalse(controllerOffersSeek())
      manager.updateLiveState(session, live = false)
      assertTrue(controllerOffersSeek())
    } finally {
      controller.release()
    }
  }
}
