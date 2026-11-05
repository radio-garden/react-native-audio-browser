package com.audiobrowser.player

import android.os.Looper
import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.sonos.SonosControl
import com.audiobrowser.sonos.SonosDevice
import com.audiobrowser.sonos.SonosOutputs
import com.audiobrowser.sonos.SonosPosition
import com.audiobrowser.sonos.SonosRemote
import com.audiobrowser.sonos.SonosRoom
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.RemoteOutputsOptions
import com.margelo.nitro.audiobrowser.TimedMetadata
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

/** A Sonos room selected in the Output Switcher, played through the player's remote switch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerSonosTest {
  private class FakeRoom : SonosControl {
    val calls = CopyOnWriteArrayList<String>()
    @Volatile var title: String? = null

    override fun setUri(
      streamUrl: String,
      title: String,
      artist: String?,
      album: String?,
      artworkUri: String?,
      live: Boolean,
    ) {
      check(!refuses) { "refused" }
      calls += "setUri $streamUrl"
    }

    @Volatile var refuses = false

    override fun play() {
      calls += "play"
    }

    override fun pause() {
      calls += "pause"
    }

    override fun stop() {
      calls += "stop"
    }

    override fun seek(positionMs: Long) {
      calls += "seek $positionMs"
    }

    @Volatile var state = "PLAYING"

    override fun getTransportState() = state

    override fun getPosition() = SonosPosition(title, null, null)

    override fun getMediaUri() = null

    override fun getVolume() = 0

    override fun setVolume(volume: Int) {
      calls += "setVolume $volume"
    }
  }

  private val looper = Looper.getMainLooper()
  private val room = FakeRoom()
  private val player =
    Player(
      RuntimeEnvironment.getApplication(),
      castRemote = { _, _ -> null },
      sonosRemote = { context, scope -> SonosRemote(context, scope, controlOf = { room }) },
    )
  private val callbacks = mock(Callbacks::class.java)

  @Before
  fun selectRoom() {
    player.setup(
      PlayerSetupOptions(
        remoteOutputs =
          RemoteOutputsOptions(cast = null, castReceiverApplicationId = null, sonos = true)
      )
    )
    player.setCallbacks(callbacks)
    player.sessionPlayer.setMediaItems(
      listOf(TrackFactory.toMedia3(TestFixtures.track(title = "a", src = "https://s/a.mp3")))
    )
    player.playWhenReady = true
    SonosOutputs.select(ROOM)
    idleUntil("the handover") { !player.localEngine.isActive }
    player.sessionPlayer.prepare()
    idleUntil("playing on the speaker") { "play" in room.calls }
  }

  private var destroyed = false

  @After
  fun release() {
    SonosOutputs.selected?.let(SonosOutputs::unselect)
    if (!destroyed) player.destroy()
  }

  @Test
  fun `a play the speaker accepts but never starts is a playback error`() {
    player.playWhenReady = false
    room.state = "PAUSED_PLAYBACK"
    idleUntil("paused on the speaker") { "pause" in room.calls }
    room.state = "TRANSITIONING"

    player.playWhenReady = true

    // Past the switch's 30 s start timeout.
    idleUntil("the start timed out", steps = 500) { player.playbackState == PlaybackState.ERROR }
  }

  @Test
  fun `unselecting a room that refused the item plays on the phone`() {
    room.refuses = true
    player.sessionPlayer.setMediaItem(
      TrackFactory.toMedia3(TestFixtures.track(title = "b", src = "https://s/b.mp3"))
    )
    idleUntil("the refusal") { player.playbackState == PlaybackState.ERROR }

    SonosOutputs.unselect(ROOM)

    idleUntil("the phone playing") { player.exoPlayer.playbackState != MediaPlayer.STATE_IDLE }
    assertEquals(true, player.exoPlayer.playWhenReady)
    assertNotEquals(PlaybackState.ERROR, player.playbackState)
  }

  @Test
  fun `destroying the player stops the speaker`() {
    player.destroy()
    destroyed = true

    idleUntil("the speaker stopped") { "stop" in room.calls }
  }

  @Test
  fun `the selected room plays the queue, and the phone stops`() {
    assertEquals(listOf("setUri https://s/a.mp3", "play"), room.calls)
    assertEquals(false, player.localEngine.isActive)
    assertEquals(MediaPlayer.STATE_IDLE, player.exoPlayer.playbackState)
  }

  @Test
  fun `a new item plays on the speaker from the position it is set at`() {
    room.calls.clear()

    player.sessionPlayer.setMediaItem(
      TrackFactory.toMedia3(TestFixtures.track(title = "b", src = "https://s/b.mp3")),
      60_000,
    )

    idleUntil("playing on the speaker") { "play" in room.calls }
    assertEquals(listOf("setUri https://s/b.mp3", "seek 60000", "play"), room.calls)
  }

  @Test
  fun `the speaker's stream title reaches the app as timed metadata`() {
    room.title = "Santana - Smooth"

    idleUntil("the title") { timedMetadata().isNotEmpty() }

    assertEquals("Santana - Smooth", timedMetadata().last().title)
  }

  @Test
  fun `unselecting the room stops the speaker and brings playback back to the phone`() {
    SonosOutputs.unselect(ROOM)

    idleUntil("the speaker stopped") { "stop" in room.calls }
    assertEquals(true, player.localEngine.isActive)
    assertEquals(true, player.exoPlayer.playWhenReady)
    assertEquals(1, player.exoPlayer.mediaItemCount)
  }

  private fun timedMetadata() =
    mockingDetails(callbacks)
      .invocations
      .filter { it.method.name == "onTimedMetadata" }
      .map { it.arguments[0] as TimedMetadata }

  /**
   * Advances the main looper 100 ms a step, and sleeps so the background thread the speaker's
   * commands run on can catch up; the speaker is polled every two seconds.
   */
  private fun idleUntil(what: String, steps: Int = 300, condition: () -> Boolean) {
    repeat(steps) {
      shadowOf(looper).idleFor(Duration.ofMillis(100))
      if (condition()) return
      Thread.sleep(10)
    }
    fail("never $what")
  }

  private companion object {
    val ROOM =
      SonosRoom(
        id = "RINCON_TEST",
        name = "Test Room",
        device =
          SonosDevice(
            udn = "uuid:RINCON_TEST",
            name = "Test Room",
            baseUrl = "http://192.0.2.1:1400",
            avTransportControlUrl = "http://192.0.2.1:1400/MediaRenderer/AVTransport/Control",
            groupRenderingControlUrl =
              "http://192.0.2.1:1400/MediaRenderer/GroupRenderingControl/Control",
            zoneGroupTopologyControlUrl = null,
          ),
      )
  }
}
