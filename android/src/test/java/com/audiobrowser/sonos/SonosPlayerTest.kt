package com.audiobrowser.sonos

import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.audiobrowser.remote.RemoteMedia
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SonosPlayerTest {

  private class FakeRoom : SonosControl {
    val calls = mutableListOf<String>()
    var state: String? = "STOPPED"
    var title: String? = null
    var level: Int? = 20
    var failing: String? = null

    private fun call(name: String) {
      calls += name
      if (failing == name) throw SoapException("$name failed", 500, SoapFault(701, null))
    }

    override fun setUri(
      streamUrl: String,
      title: String,
      artist: String?,
      album: String?,
      artworkUri: String?,
      live: Boolean,
    ) = call("setUri $streamUrl $title live=$live")

    override fun play() = call("play")

    override fun pause() = call("pause")

    override fun stop() = call("stop")

    override fun getTransportState() = state

    override fun getStreamContent() = title

    override fun getVolume() = level

    override fun setVolume(volume: Int) = call("setVolume $volume")
  }

  private val dispatcher = StandardTestDispatcher()
  private val scope = TestScope(dispatcher)
  private val room = FakeRoom()
  private val resolved =
    mapOf("a" to RemoteMedia("https://a.example/stream", "audio/mpeg", null, live = true))
  private val errors = mutableListOf<PlaybackException>()
  private val player =
    SonosPlayer(
      Looper.getMainLooper(),
      room,
      mediaOf = { resolved[it.mediaId] },
      onError = { errors += it },
      routingControllerId = { "routing-session-1" },
      scope = scope,
      io = dispatcher,
      pollIntervalMs = 1_000,
      startTimeoutMs = 10_000,
      nowMs = { dispatcher.scheduler.currentTime },
    )

  @After
  fun tearDown() {
    player.release()
    scope.runCurrent()
  }

  private fun station(id: String, uri: String = "https://$id.example/raw") =
    MediaItem.Builder()
      .setMediaId(id)
      .setUri(uri)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle("Station $id")
          .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
          .build()
      )
      .build()

  private fun track(id: String) =
    MediaItem.Builder()
      .setMediaId(id)
      .setUri("https://$id.example/track.mp3")
      .setMediaMetadata(MediaMetadata.Builder().setTitle("Track $id").build())
      .build()

  /** Runs the commands and one poll. */
  private fun settle() {
    scope.runCurrent()
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun poll() {
    scope.advanceTimeBy(1_000)
    settle()
  }

  private fun startPlaying(vararg items: MediaItem) {
    player.setMediaItems(items.toList())
    player.playWhenReady = true
    player.prepare()
    settle()
  }

  @Test
  fun `prepare loads the resolved URL of the current item and plays it`() {
    startPlaying(station("a"))

    assertEquals(listOf("setUri https://a.example/stream Station a live=true", "play"), room.calls)
    assertEquals(Player.STATE_BUFFERING, player.playbackState)
  }

  @Test
  fun `an unresolved item loads its own URL`() {
    startPlaying(station("b"))

    assertEquals("setUri https://b.example/raw Station b live=true", room.calls.first())
  }

  @Test
  fun `setting the items loads the current one, without playing it unless asked`() {
    player.setMediaItems(listOf(station("a")))
    settle()

    assertEquals(listOf("setUri https://a.example/stream Station a live=true"), room.calls)
  }

  @Test
  fun `setting the items again after a failure loads them again`() {
    room.failing = "play"
    startPlaying(station("a"))
    room.failing = null
    room.calls.clear()

    player.setMediaItems(listOf(station("a")))
    settle()

    assertEquals(listOf("setUri https://a.example/stream Station a live=true", "play"), room.calls)
  }

  @Test
  fun `an empty queue stops the speaker`() {
    startPlaying(station("a"))
    room.calls.clear()

    player.setMediaItems(emptyList())
    settle()

    assertEquals(listOf("stop"), room.calls)
    assertEquals(Player.STATE_IDLE, player.playbackState)
  }

  @Test
  fun `the speaker playing makes the player ready and playing`() {
    startPlaying(station("a"))
    room.state = "PLAYING"
    poll()

    assertEquals(Player.STATE_READY, player.playbackState)
    assertTrue(player.isPlaying)
  }

  @Test
  fun `pause and play act on the speaker`() {
    startPlaying(station("a"))
    room.calls.clear()

    player.pause()
    settle()
    player.play()
    settle()

    assertEquals(listOf("pause", "play"), room.calls)
  }

  @Test
  fun `a stream that refuses pause is stopped instead`() {
    startPlaying(station("a"))
    room.calls.clear()
    room.failing = "pause"

    player.pause()
    settle()

    assertEquals(listOf("pause", "stop"), room.calls)
    assertTrue(errors.isEmpty())
  }

  @Test
  fun `skipping loads the next item on the speaker and plays it`() {
    startPlaying(station("a"), station("b"))
    room.calls.clear()

    player.seekToNextMediaItem()
    settle()

    assertEquals(listOf("setUri https://b.example/raw Station b live=true", "play"), room.calls)
    assertEquals(1, player.currentMediaItemIndex)
  }

  @Test
  fun `a metadata-only replacement of the current item does not reload the speaker`() {
    startPlaying(station("a"))
    room.calls.clear()

    player.replaceMediaItem(
      0,
      station("a")
        .buildUpon()
        .setMediaMetadata(MediaMetadata.Builder().setTitle("New").build())
        .build(),
    )
    settle()

    assertEquals(emptyList<String>(), room.calls)
    assertEquals("New", player.currentMediaItem?.mediaMetadata?.title)
  }

  @Test
  fun `the speaker's stream title arrives as an ICY title`() {
    val titles = mutableListOf<String?>()
    player.addListener(
      object : Player.Listener {
        override fun onMetadata(metadata: Metadata) {
          titles += (metadata[0] as IcyInfo).title
        }
      }
    )
    startPlaying(station("a"))
    room.state = "PLAYING"
    room.title = "Santana - Smooth"
    poll()
    poll()
    room.title = "TYPE=SNG|TITLE Le Son Pop-Rock|ARTIST Carole Vega|ALBUM "
    poll()

    assertEquals(listOf("Santana - Smooth", "Carole Vega - Le Son Pop-Rock"), titles)
  }

  @Test
  fun `pausing on the speaker clears the intent to play`() {
    startPlaying(station("a"))
    room.state = "PLAYING"
    poll()
    room.state = "PAUSED_PLAYBACK"
    poll()

    assertFalse(player.playWhenReady)
  }

  @Test
  fun `a live stream stopped on the speaker clears the intent to play, and play reloads it`() {
    startPlaying(station("a"))
    room.state = "PLAYING"
    poll()
    room.state = "STOPPED"
    poll()
    assertFalse(player.playWhenReady)
    room.calls.clear()

    player.play()
    settle()

    assertEquals(listOf("setUri https://a.example/stream Station a live=true", "play"), room.calls)
  }

  @Test
  fun `a finished track moves on to the next one`() {
    startPlaying(track("t1"), track("t2"))
    room.state = "PLAYING"
    poll()
    room.calls.clear()
    room.state = "STOPPED"
    poll()

    assertEquals(1, player.currentMediaItemIndex)
    assertEquals(
      listOf("setUri https://t2.example/track.mp3 Track t2 live=false", "play"),
      room.calls,
    )
  }

  @Test
  fun `with repeat all, the last finished track moves on to the first`() {
    player.repeatMode = Player.REPEAT_MODE_ALL
    startPlaying(track("t1"), track("t2"))
    player.seekToNextMediaItem()
    settle()
    room.state = "PLAYING"
    poll()
    room.calls.clear()
    room.state = "STOPPED"
    poll()

    assertEquals(0, player.currentMediaItemIndex)
    assertEquals(
      listOf("setUri https://t1.example/track.mp3 Track t1 live=false", "play"),
      room.calls,
    )
  }

  @Test
  fun `a poll that overlapped a command does not undo it`() {
    startPlaying(station("a"))
    room.state = "PLAYING"
    poll()
    // The poll reads PLAYING, then pause is pressed before its reading is applied.
    room.state = "PLAYING"
    scope.advanceTimeBy(1_000)
    player.pause()
    settle()

    assertFalse(player.playWhenReady)
  }

  @Test
  fun `a failed command is reported, and leaves the player idle`() {
    room.failing = "play"
    startPlaying(station("a"))

    assertEquals(listOf(PlaybackException.ERROR_CODE_REMOTE_ERROR), errors.map { it.errorCode })
    assertEquals(Player.STATE_IDLE, player.playbackState)
  }

  @Test
  fun `a stream the speaker never starts is reported as a failure`() {
    startPlaying(station("a"))
    repeat(12) { poll() }

    assertEquals(listOf(PlaybackException.ERROR_CODE_REMOTE_ERROR), errors.map { it.errorCode })
  }

  @Test
  fun `the volume is the speaker's`() {
    startPlaying(station("a"))
    poll()
    assertEquals(20, player.deviceVolume)
    room.calls.clear()

    player.setDeviceVolume(7, 0)
    settle()

    assertEquals(listOf("setVolume 7"), room.calls)
    assertEquals(7, player.deviceVolume)
  }

  @Test
  fun `the device is the room's system routing session, so the output chip can reach it`() {
    assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, player.deviceInfo.playbackType)
    assertEquals("routing-session-1", player.deviceInfo.routingControllerId)
  }

  @Test
  fun `stop stops the speaker and the polling`() {
    startPlaying(station("a"))
    room.calls.clear()

    player.stop()
    settle()
    room.state = "PLAYING"
    poll()

    assertEquals(listOf("stop"), room.calls)
    assertEquals(Player.STATE_IDLE, player.playbackState)
  }

  @Test
  fun `streamTitle reads both forms the speaker reports`() {
    assertEquals("Santana - Smooth", streamTitle("Santana - Smooth"))
    assertEquals(
      "Carole Vega - Le Son",
      streamTitle("TYPE=SNG|TITLE Le Son|ARTIST Carole Vega|ALBUM "),
    )
    assertEquals("Le Son", streamTitle("TYPE=SNG|TITLE Le Son|ARTIST |ALBUM "))
    assertNull(streamTitle("  "))
  }

  @Test
  fun `streamTitle skips the speaker's own status while it connects`() {
    assertNull(streamTitle("ZPSTR_CONNECTING"))
    assertNull(streamTitle("ZPSTR_BUFFERING"))
  }
}
