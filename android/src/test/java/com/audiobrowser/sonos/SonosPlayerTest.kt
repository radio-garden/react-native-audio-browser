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
    var positionMs: Long? = null
    var durationMs: Long? = null
    var trackUri: String? = null
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

    override fun getPosition() = SonosPosition(title, positionMs, durationMs, trackUri)

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
  fun `a track that plays to its end moves on to the next one`() {
    startPlaying(track("t1"), track("t2"))
    room.state = "PLAYING"
    room.durationMs = 200_000
    room.positionMs = 199_500
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
    room.durationMs = 200_000
    room.positionMs = 199_500
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
    startPlaying(track("t1"), track("t2"))
    room.state = "PLAYING"
    room.durationMs = 200_000
    room.positionMs = 30_000
    poll()
    room.calls.clear()
    room.state = "STOPPED"
    poll()

    assertEquals(0, player.currentMediaItemIndex)
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
    player.setMediaItems(listOf(station("a"), station("b")))
    player.playWhenReady = true
    player.seekToNextMediaItem()
    settle()

    assertTrue(errors.isEmpty())
    assertEquals(
      listOf("setUri https://b.example/raw Station b live=true", "play"),
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
  fun `playing an item loaded paused long ago gives it the whole start time`() {
    player.setMediaItems(listOf(station("a")))
    settle()
    repeat(30) { poll() }

    player.play()
    settle()
    poll()

    assertTrue(errors.isEmpty())
    assertTrue(player.playWhenReady)
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
    room.trackUri = "x-rincon-mp3radio://https://a.example/stream"
    poll()
    room.trackUri = "x-sonos-spotify:spotify%3atrack%3a1"
    room.title = "Someone else - Their song"
    poll()

    assertFalse(player.playWhenReady)
    room.calls.clear()
    player.stop()
    settle()
    assertEquals(emptyList<String>(), room.calls)
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
  fun `a stream stuck transitioning is reported once the start times out`() {
    room.state = "TRANSITIONING"
    startPlaying(station("a"))
    repeat(12) { poll() }

    assertEquals(listOf(PlaybackException.ERROR_CODE_REMOTE_ERROR), errors.map { it.errorCode })
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
