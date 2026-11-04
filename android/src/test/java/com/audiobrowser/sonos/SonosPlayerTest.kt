package com.audiobrowser.sonos

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.audiobrowser.remote.remoteItemOf
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
    var positionMs: Long? = null
    var durationMs: Long? = null
    var currentUri: String? = null
    var failing: String? = null
    var unreachable = false

    /** Runs once, while the next poll reads the speaker. */
    var duringRead: (() -> Unit)? = null

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

    override fun seek(positionMs: Long) = call("seek $positionMs")

    override fun getTransportState(): String? {
      if (unreachable) throw java.io.IOException("no route to host")
      duringRead?.also { duringRead = null }?.invoke()
      return state
    }

    override fun getPosition() = SonosPosition(title, positionMs, durationMs)

    override fun getMediaUri() = currentUri

    override fun getVolume() = level

    override fun setVolume(volume: Int) = call("setVolume $volume")
  }

  private val dispatcher = StandardTestDispatcher()
  private val scope = TestScope(dispatcher)
  private val room = FakeRoom()
  private val errors = mutableListOf<PlaybackException>()
  private val volumes = mutableListOf<Int>()
  private var ended = 0
  private val player =
    SonosPlayer(
      Looper.getMainLooper(),
      room,
      onEnded = { ended++ },
      onError = { errors += it },
      onVolume = { volumes += it },
      routingControllerId = { "routing-session-1" },
      scope = scope,
      io = dispatcher,
      pollIntervalMs = 1_000,
      nowMs = { dispatcher.scheduler.currentTime },
    )

  @After
  fun tearDown() {
    player.release()
    scope.runCurrent()
  }

  private fun station(id: String) =
    remoteItemOf(
      MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Station $id").build())
        .build(),
      "https://$id.example/stream",
      null,
      live = true,
    )

  private fun track(id: String) =
    remoteItemOf(
      MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Track $id").build())
        .build(),
      "https://$id.example/track.mp3",
      null,
      live = false,
    )

  /** Runs the commands and one poll. */
  private fun settle() {
    scope.runCurrent()
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun poll() {
    scope.advanceTimeBy(1_000)
    settle()
  }

  private fun startPlaying(item: MediaItem, positionMs: Long = C.TIME_UNSET) {
    player.playWhenReady = true
    player.setMediaItem(item, positionMs)
    settle()
  }

  @Test
  fun `setting the item loads it on the speaker and plays it`() {
    startPlaying(station("a"))

    assertEquals(listOf("setUri https://a.example/stream Station a live=true", "play"), room.calls)
    assertEquals(Player.STATE_BUFFERING, player.playbackState)
  }

  @Test
  fun `a track starts at the position it is set from`() {
    startPlaying(track("t1"), positionMs = 90_000)

    assertEquals(
      listOf("setUri https://t1.example/track.mp3 Track t1 live=false", "seek 90000", "play"),
      room.calls,
    )
    assertEquals(90_000, player.currentPosition)
  }

  @Test
  fun `a station starts at its live edge, whatever the position it is set from`() {
    startPlaying(station("a"), positionMs = 90_000)

    assertEquals(listOf("setUri https://a.example/stream Station a live=true", "play"), room.calls)
  }

  @Test
  fun `a start position the speaker refuses still plays the track`() {
    room.failing = "seek 90000"
    startPlaying(track("t1"), positionMs = 90_000)

    assertEquals("play", room.calls.last())
    assertTrue(errors.isEmpty())
  }

  @Test
  fun `an item set paused loads without playing`() {
    player.setMediaItem(station("a"))
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
  fun `another item takes the last one's place on the speaker`() {
    startPlaying(station("a"))
    room.calls.clear()

    player.setMediaItem(station("b"))
    settle()

    assertEquals(listOf("setUri https://b.example/stream Station b live=true", "play"), room.calls)
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
  fun `a track that plays to its end is reported ended`() {
    startPlaying(track("t1"))
    room.state = "PLAYING"
    room.durationMs = 200_000
    room.positionMs = 199_500
    poll()
    room.calls.clear()
    room.state = "STOPPED"
    poll()

    assertEquals(1, ended)
    assertEquals(Player.STATE_ENDED, player.playbackState)
    assertEquals(emptyList<String>(), room.calls)
  }

  @Test
  fun `the end of the last track stays ended`() {
    startPlaying(track("t1"))
    room.state = "PLAYING"
    room.durationMs = 200_000
    room.positionMs = 198_500
    poll()
    room.state = "STOPPED"
    poll()
    assertEquals(Player.STATE_ENDED, player.playbackState)

    poll()

    assertEquals(Player.STATE_ENDED, player.playbackState)
    assertEquals(1, ended)
  }

  @Test
  fun `the last track ends at its duration`() {
    startPlaying(track("t1"))
    room.state = "PLAYING"
    room.durationMs = 200_000
    room.positionMs = 199_000
    poll()
    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
    room.state = "STOPPED"
    poll()

    assertEquals(Player.STATE_ENDED, player.playbackState)
    assertEquals(200_000, player.currentPosition)
  }

  @Test
  fun `a poll that overlapped a command does not undo it`() {
    startPlaying(station("a"))
    room.state = "PLAYING"
    poll()
    room.duringRead = { player.pause() }
    poll()

    assertFalse(player.playWhenReady)
  }

  @Test
  fun `a track stopped on the speaker before its end reads as paused, not as its end`() {
    startPlaying(track("t1"))
    room.state = "PLAYING"
    room.durationMs = 200_000
    room.positionMs = 30_000
    poll()
    room.calls.clear()
    room.state = "STOPPED"
    poll()

    assertEquals(0, ended)
    assertFalse(player.playWhenReady)
    assertEquals(emptyList<String>(), room.calls)
  }

  @Test
  fun `a track's position and duration are the speaker's`() {
    startPlaying(track("t1"))
    room.state = "PLAYING"
    room.durationMs = 200_000
    room.positionMs = 30_000
    poll()

    assertEquals(200_000, player.duration)
    assertTrue(player.currentPosition >= 30_000)
  }

  @Test
  fun `a failure that a later command replaced is not reported`() {
    room.failing = "setUri https://a.example/stream Station a live=true"
    player.playWhenReady = true
    player.setMediaItem(station("a"))
    player.setMediaItem(station("b"))
    settle()

    assertTrue(errors.isEmpty())
    assertEquals(
      listOf("setUri https://b.example/stream Station b live=true", "play"),
      room.calls.takeLast(2),
    )
  }

  @Test
  fun `a command that fails after release reports nothing`() {
    room.failing = "stop"
    startPlaying(station("a"))
    player.stop()
    player.release()
    settle()

    assertTrue(errors.isEmpty())
  }

  @Test
  fun `a stop still reaches the speaker once the scope has ended`() {
    startPlaying(station("a"))
    room.calls.clear()

    player.stop()
    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    settle()

    assertEquals(listOf("stop"), room.calls)
  }

  @Test
  fun `after a failure, stopping still stops the speaker`() {
    room.failing = "play"
    startPlaying(station("a"))
    room.failing = null
    room.calls.clear()

    player.stop()
    settle()

    assertEquals(listOf("stop"), room.calls)
  }

  @Test
  fun `after a failure, pausing still pauses the speaker`() {
    room.failing = "play"
    startPlaying(station("a"))
    room.failing = null
    room.calls.clear()

    player.pause()
    settle()

    assertEquals(listOf("pause"), room.calls)
  }

  @Test
  fun `a failed volume change is only logged`() {
    room.failing = "setVolume 7"
    startPlaying(station("a"))

    player.setDeviceVolume(7, 0)
    settle()

    assertTrue(errors.isEmpty())
    assertEquals(Player.STATE_BUFFERING, player.playbackState)
  }

  @Test
  fun `a volume change doesn't hide a failed load`() {
    room.failing = "setUri https://a.example/stream Station a live=true"
    player.setMediaItems(listOf(station("a")))
    player.setDeviceVolume(5, 0)
    settle()

    assertEquals(listOf(PlaybackException.ERROR_CODE_REMOTE_ERROR), errors.map { it.errorCode })
  }

  @Test
  fun `another app taking the room reads as paused, and the room is left alone`() {
    startPlaying(station("a"))
    room.state = "PLAYING"
    room.currentUri = "x-rincon-mp3radio://https://a.example/stream"
    poll()
    room.currentUri = "x-sonos-vli:RINCON_A:2,spotify:abc"
    room.title = "Someone else - Their song"
    poll()

    assertFalse(player.playWhenReady)
    room.calls.clear()
    player.stop()
    settle()
    assertEquals(emptyList<String>(), room.calls)
  }

  @Test
  fun `a room holding another URI that contains ours is another app's`() {
    startPlaying(station("a"))
    room.state = "PLAYING"
    room.currentUri = "x-sonos-http:proxy?u=https://a.example/stream&x=1"
    poll()

    assertFalse(player.playWhenReady)
  }

  @Test
  fun `pausing stops the position running`() {
    startPlaying(track("t1"))
    room.state = "PLAYING"
    room.durationMs = 200_000
    room.positionMs = 30_000
    poll()

    player.pause()
    settle()
    val paused = player.currentPosition
    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))

    assertEquals(paused, player.currentPosition)
  }

  @Test
  fun `seeking within a track seeks on the speaker`() {
    startPlaying(track("t1"))
    room.state = "PLAYING"
    room.durationMs = 200_000
    room.positionMs = 30_000
    poll()
    room.calls.clear()

    player.seekTo(60_000)
    settle()

    assertEquals(listOf("seek 60000"), room.calls)
  }

  @Test
  fun `seeking while playing keeps the position running`() {
    startPlaying(track("t1"))
    room.state = "PLAYING"
    room.durationMs = 200_000
    room.positionMs = 30_000
    poll()

    player.seekTo(60_000)
    settle()
    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))

    assertTrue(player.currentPosition > 60_000)
  }

  @Test
  fun `a seek the speaker refuses leaves the track playing`() {
    startPlaying(track("t1"))
    room.state = "PLAYING"
    room.durationMs = 200_000
    room.positionMs = 30_000
    poll()
    room.failing = "seek 60000"

    player.seekTo(60_000)
    settle()

    assertEquals(emptyList<PlaybackException>(), errors)
    assertEquals(Player.STATE_READY, player.playbackState)
  }

  @Test
  fun `a track is seekable, a station is not`() {
    startPlaying(track("t1"))
    assertTrue(player.isCurrentMediaItemSeekable)

    startPlaying(station("a"))
    assertFalse(player.isCurrentMediaItemSeekable)
  }

  @Test
  fun `seeking to the default position of a station loads it again`() {
    startPlaying(station("a"))
    room.state = "PLAYING"
    poll()
    room.calls.clear()

    player.seekToDefaultPosition()
    settle()

    assertEquals(listOf("setUri https://a.example/stream Station a live=true", "play"), room.calls)
  }

  @Test
  fun `a speaker that stops answering is reported`() {
    startPlaying(station("a"))
    room.state = "PLAYING"
    poll()
    room.unreachable = true
    repeat(3) { poll() }

    assertEquals(listOf(PlaybackException.ERROR_CODE_REMOTE_ERROR), errors.map { it.errorCode })
    assertEquals(Player.STATE_IDLE, player.playbackState)
  }

  @Test
  fun `a failed command is reported, and leaves the player idle`() {
    room.failing = "play"
    startPlaying(station("a"))

    assertEquals(listOf(PlaybackException.ERROR_CODE_REMOTE_ERROR), errors.map { it.errorCode })
    assertEquals(Player.STATE_IDLE, player.playbackState)
  }

  @Test
  fun `the volume is read when the player starts, before anything loads`() {
    assertFalse(player.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))

    settle()

    assertEquals(20, player.deviceVolume)
    assertEquals(listOf(20), volumes)
    assertTrue(player.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))
  }

  @Test
  fun `a volume set before the first read is kept`() {
    player.setDeviceVolume(5, 0)
    settle()

    assertEquals(5, player.deviceVolume)
    assertEquals(listOf(5), volumes)
  }

  @Test
  fun `the volume is the speaker's`() {
    startPlaying(station("a"))
    room.level = 30
    poll()
    assertEquals(30, player.deviceVolume)
    room.calls.clear()

    player.setDeviceVolume(7, 0)
    settle()

    assertEquals(listOf("setVolume 7"), room.calls)
    assertEquals(7, player.deviceVolume)
    assertEquals(listOf(20, 30, 7), volumes)
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
