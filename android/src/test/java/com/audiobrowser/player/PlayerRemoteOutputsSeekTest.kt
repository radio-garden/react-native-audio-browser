package com.audiobrowser.player

import android.net.Uri
import android.os.Looper
import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.remote.FakeRemote
import com.audiobrowser.silentWavBytes
import com.audiobrowser.silentWavUri
import com.margelo.nitro.audiobrowser.RemoteOutputsOptions
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/** With a remote output configured but no session, a seek right after a load lands on the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerRemoteOutputsSeekTest {
  @get:Rule val folder = TemporaryFolder()

  private val looper = Looper.getMainLooper()
  private val player =
    Player(
      RuntimeEnvironment.getApplication(),
      castRemote = { _, _ -> FakeRemote(looper) },
      sonosRemote = { _, _ -> FakeRemote(looper) },
    )

  @Before
  fun setUp() {
    player.setup(
      PlayerSetupOptions(
        remoteOutputs =
          RemoteOutputsOptions(cast = true, castReceiverApplicationId = null, sonos = true)
      )
    )
    assertFalse(player.sessionPlayer === player.exoPlayer)
  }

  @After fun release() = player.destroy()

  private fun idleUntilReady() {
    repeat(200) {
      if (player.exoPlayer.playbackState == MediaPlayer.STATE_READY) return
      ShadowSystemClock.advanceBy(Duration.ofMillis(100))
      Thread.sleep(10)
      shadowOf(looper).idle()
    }
    fail("the engine never became ready, in state ${player.exoPlayer.playbackState}")
  }

  private fun track() =
    TestFixtures.track(id = "a", src = folder.silentWavUri(Duration.ofMinutes(2)))

  @Test
  fun `a seek right after a load lands`() {
    player.load(track())
    player.seekTo(60, TimeUnit.SECONDS)

    idleUntilReady()

    assertEquals(60_000L, player.exoPlayer.currentPosition)
    assertEquals(60_000L, player.position)
  }

  @Test
  fun `a seek right after a load that replaces the current track lands`() {
    player.load(track())
    idleUntilReady()

    // Another file: media3 updates an item of the same URI in place, without a placeholder.
    val other =
      folder.newFile("other.wav").apply { writeBytes(silentWavBytes(Duration.ofMinutes(2))) }
    player.load(TestFixtures.track(id = "b", src = Uri.fromFile(other).toString()))
    player.seekTo(60, TimeUnit.SECONDS)
    idleUntilReady()

    assertEquals("b", player.currentTrack?.id)
    assertEquals(60_000L, player.exoPlayer.currentPosition)
  }

  @Test
  fun `a setQueue at a position then starts there`() {
    player.setQueue(arrayOf(track()), 0, 60_000)

    idleUntilReady()

    assertEquals(60_000L, player.exoPlayer.currentPosition)
    assertEquals(60_000L, player.position)
  }
}
