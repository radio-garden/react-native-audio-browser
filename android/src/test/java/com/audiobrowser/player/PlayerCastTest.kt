package com.audiobrowser.player

import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.remote.FakePlayer
import com.audiobrowser.remote.FakeRemote
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.PlaybackState
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What the player leaves to a Cast receiver while one plays: the local-only work steps aside. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerCastTest {
  private val looper = Looper.getMainLooper()
  private val player = Player(RuntimeEnvironment.getApplication())
  private val remote = FakeRemote(looper)
  private val receiver = remote.player
  private val callbacks = mock(Callbacks::class.java)

  @Before
  fun cast() {
    player.castRemoteFactory = { _, _ -> remote }
    player.setup(PlayerSetupOptions(cast = true))
    player.setCallbacks(callbacks)
    player.sessionPlayer.setMediaItems(
      listOf(TrackFactory.toMedia3(TestFixtures.track(title = "a", src = "https://s/a.mp3")))
    )
    player.playWhenReady = true
    remote.startSession()
    idleUntil("casting") { !player.isPlayingLocally }
  }

  @After fun release() = player.destroy()

  @Test
  fun `a receiver falling behind the live window is an error, not a local restart`() {
    remote.fail(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)
    idle()

    assertEquals(PlaybackState.ERROR, player.playbackState)
    assertTrue(calls("onPlaybackError").isNotEmpty())
  }

  @Test
  fun `a receiver failing to play is an error, not a local restart`() {
    remote.fail()
    idle()

    assertEquals(PlaybackState.ERROR, player.playbackState)
    assertTrue(calls("onPlaybackError").isNotEmpty())
    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
  }

  @Test
  fun `a new queue while casting goes to the receiver`() {
    player.setQueue(
      arrayOf(
        TestFixtures.track(title = "b", src = "https://s/b.mp3"),
        TestFixtures.track(title = "c", src = "https://s/c.mp3"),
      ),
      startIndex = 1,
    )
    idleUntil("the queue on the receiver") { receiver.items.size == 2 }

    assertEquals(1, receiver.index)
  }

  @Test
  fun `the volume moves the receiver's volume, not the local one`() {
    player.volume = 0.5f
    idle()

    assertEquals(FakePlayer.MAX_VOLUME / 2, receiver.deviceVolume)
    assertEquals(1f, player.exoPlayer.volume)
    assertEquals(0.5f, player.volume)
  }

  @Test
  fun `the sleep timer pauses the receiver without fading its volume`() {
    player.sleepTimer.setAfter(seconds = 2.0, fadeDuration = 2.0)
    shadowOf(looper).idleFor(Duration.ofSeconds(3))

    assertFalse(receiver.playWhenReady)
    assertEquals(emptyList<Int>(), receiver.volumeChanges)
  }

  private fun calls(name: String) =
    mockingDetails(callbacks).invocations.filter { it.method.name == name }

  private fun idle() = shadowOf(looper).idle()

  /** Casting starts after a resolution on a background thread. */
  private fun idleUntil(what: String, condition: () -> Boolean) {
    repeat(200) {
      idle()
      if (condition()) return
      Thread.sleep(10)
    }
    fail("never $what")
  }
}
