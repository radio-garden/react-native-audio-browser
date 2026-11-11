package com.audiobrowser.remote

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RemoteSwitchingPlayerTest {
  private val looper = Looper.getMainLooper()
  private val scope = MainScope()
  private val local = FakeLocalPlayer(looper)
  private val remote = FakeRemote(looper)
  private val other = FakeRemote(looper)
  private val resolved = mutableListOf<String>()
  private val live = mutableSetOf<String>()
  private val unresolvable = mutableSetOf<String>()

  private var gate: CompletableDeferred<Unit>? = null

  private val player =
    RemoteSwitchingPlayer(
      local,
      listOf(remote, other),
      resolve = { item ->
        gate?.await()
        check(item.mediaId !in unresolvable) { "no URL" }
        resolved += item.mediaId
        remoteItemOf(item, "https://cdn.example/${item.mediaId}", null, item.mediaId in live)
      },
      scope = scope,
    )

  @After fun cancel() = scope.cancel()

  @Test
  fun `a session takes the current item to the receiver at its position and play intent`() {
    playLocally(listOf("a", "b", "c"), index = 1, positionMs = 5_000)

    remote.startSession()
    idle()

    assertTrue(player.isRemote)
    assertEquals(listOf(Triple("b", 5_000L, true)), remote.player.loads.map { it.short() })
    assertEquals(
      "https://cdn.example/b",
      remote.player.loads.single().item.localConfiguration?.uri.toString(),
    )
    assertEquals(listOf("b"), resolved)
    assertEquals(1, local.stops)
    assertEquals(listOf("a", "b", "c"), queue())
    assertEquals(1, player.currentMediaItemIndex)
    assertEquals("b", player.currentMediaItem?.mediaId)
  }

  @Test
  fun `the phone plays on until the receiver's item is resolved`() {
    playLocally(listOf("a"))
    gate = CompletableDeferred()

    remote.startSession()
    idle()

    assertFalse(player.isRemote)
    assertEquals(0, local.stops)
    assertTrue(remote.player.loads.isEmpty())

    gate!!.complete(Unit)
    idle()
    assertTrue(player.isRemote)
  }

  @Test
  fun `an item the phone moves on to while resolving is the one handed over`() {
    playLocally(listOf("a", "b"))
    gate = CompletableDeferred()
    remote.startSession()
    idle()

    local.moveTo(1)
    gate!!.complete(Unit)
    idle()

    assertEquals(listOf("b"), remote.player.loads.map { it.item.mediaId })
    assertEquals(1, player.currentMediaItemIndex)
  }

  @Test
  fun `a live item starts at the receiver's live edge`() {
    live += "a"
    playLocally(listOf("a"), positionMs = 90_000)

    remote.startSession()
    idle()

    assertEquals(C.TIME_UNSET, remote.player.loads.single().positionMs)
  }

  @Test
  fun `an unprepared queue goes over unloaded, and prepare loads it at its position`() {
    local.setMediaItems(items("a"), 0, 3_000)
    idle()

    remote.startSession()
    idle()
    assertTrue(player.isRemote)
    assertTrue(remote.player.loads.isEmpty())
    assertEquals(MediaPlayer.STATE_IDLE, player.playbackState)

    player.prepare()
    idle()
    assertEquals(3_000, remote.player.loads.single().positionMs)
  }

  @Test
  fun `the session ending brings the queue, its position and the play intent back`() {
    playLocally(listOf("a", "b"))
    remote.startSession()
    idle()
    val receiver = remote.player
    receiver.positionMs = 7_000
    receiver.update()
    idle()

    remote.endSession()
    idle()

    assertFalse(player.isRemote)
    assertEquals(listOf("a", "b"), local.items.map { it.mediaId })
    assertEquals(0, local.index)
    assertEquals(7_000, local.positionMs)
    assertTrue(local.playWhenReady)
    assertEquals(2, local.prepares)
    assertEquals(1, receiver.stops)
  }

  @Test
  fun `the session ending never shows the receiver's own item`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    val shown = mutableListOf<String?>()
    player.addListener(
      object : MediaPlayer.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
          shown += mediaItem?.localConfiguration?.uri?.toString()
        }
      }
    )

    remote.endSession()
    idle()

    assertEquals(emptyList<String>(), shown.filterNot { it == "https://s/a" })
  }

  @Test
  fun `the session ending after the receiver failed plays the item on the phone where it was`() {
    playLocally(listOf("a", "b"), index = 1)
    remote.startSession()
    idle()
    remote.player.positionMs = 7_000
    remote.player.update()
    remote.fail()
    idle()
    assertEquals(PlaybackException.ERROR_CODE_REMOTE_ERROR, player.playerError?.errorCode)

    remote.endSession()
    idle()

    assertFalse(player.isRemote)
    assertEquals(1, local.index)
    assertEquals(7_000, local.positionMs)
    assertTrue(local.playWhenReady)
    assertEquals(2, local.prepares)
    assertNull(player.playerError)
  }

  @Test
  fun `the session ending after the item didn't resolve plays it on the phone`() {
    unresolvable += "a"
    playLocally(listOf("a"), positionMs = 4_000)
    remote.startSession()
    idle()
    assertEquals(PlaybackException.ERROR_CODE_REMOTE_ERROR, player.playerError?.errorCode)

    remote.endSession()
    idle()

    assertEquals(4_000, local.positionMs)
    assertTrue(local.playWhenReady)
    assertEquals(2, local.prepares)
  }

  @Test
  fun `the session ending after a live item didn't resolve plays it on the phone at its live edge`() {
    unresolvable += "a"
    local.setMediaItems(listOf(item("a").buildUpon().setLiveConfiguration(LIVE).build()), 0, 90_000)
    local.playWhenReady = true
    local.prepare()
    idle()
    remote.startSession()
    idle()

    remote.endSession()
    idle()

    assertEquals(0, local.positionMs)
    assertEquals(2, local.prepares)
  }

  @Test
  fun `the session ending after the receiver didn't start in time plays the item on the phone`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    remote.player.lagging = true
    player.seekToDefaultPosition()
    idle()
    shadowOf(looper).idleFor(Duration.ofSeconds(31))
    assertEquals(PlaybackException.ERROR_CODE_REMOTE_ERROR, player.playerError?.errorCode)

    remote.endSession()
    idle()

    assertTrue(local.playWhenReady)
    assertEquals(2, local.prepares)
  }

  @Test
  fun `the session ending after a stop leaves the phone unprepared`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    player.stop()
    idle()

    remote.endSession()
    idle()

    assertEquals(1, local.prepares)
  }

  @Test
  fun `the receiver's end plays the next item from its start`() {
    playLocally(listOf("a", "b"), positionMs = 4_000)
    remote.startSession()
    idle()

    remote.end()
    idle()

    assertEquals(listOf("a", "b"), remote.player.loads.map { it.item.mediaId })
    assertEquals(C.TIME_UNSET, remote.player.loads.last().positionMs)
    assertEquals(1, player.currentMediaItemIndex)
  }

  @Test
  fun `the last item's end ends playback`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()

    remote.end()
    idle()

    assertEquals(MediaPlayer.STATE_ENDED, player.playbackState)
    assertEquals(1, remote.player.loads.size)
  }

  @Test
  fun `repeat one plays the item again, and repeat all goes back to the first`() {
    playLocally(listOf("a", "b"), index = 1)
    remote.startSession()
    idle()

    player.repeatMode = MediaPlayer.REPEAT_MODE_ONE
    remote.end()
    idle()
    player.repeatMode = MediaPlayer.REPEAT_MODE_ALL
    remote.end()
    idle()

    assertEquals(listOf("b", "b", "a"), remote.player.loads.map { it.item.mediaId })
    assertEquals(MediaPlayer.REPEAT_MODE_ALL, player.repeatMode)
  }

  @Test
  fun `repeat and shuffle go over and come back`() {
    playLocally(listOf("a", "b"))
    local.repeatMode = MediaPlayer.REPEAT_MODE_ALL
    local.shuffleModeEnabled = true
    idle()

    remote.startSession()
    idle()
    assertEquals(MediaPlayer.REPEAT_MODE_ALL, player.repeatMode)
    assertTrue(player.shuffleModeEnabled)

    player.shuffleModeEnabled = false
    player.repeatMode = MediaPlayer.REPEAT_MODE_ONE
    remote.endSession()
    idle()

    assertFalse(local.shuffleModeEnabled)
    assertEquals(MediaPlayer.REPEAT_MODE_ONE, local.repeatMode)
  }

  @Test
  fun `edits that leave the current item in place don't reload the receiver`() {
    playLocally(listOf("a", "b", "c"), index = 1)
    remote.startSession()
    idle()

    player.addMediaItems(0, items("x"))
    player.removeMediaItem(3)
    player.moveMediaItem(0, 2)
    player.replaceMediaItem(0, item("a").buildUpon().setMediaMetadata(titled("now")).build())
    idle()

    assertEquals(1, remote.player.loads.size)
    assertEquals(listOf("a", "b", "x"), queue())
    assertEquals("b", player.currentMediaItem?.mediaId)
    assertEquals(MediaPlayer.STATE_READY, player.playbackState)
  }

  @Test
  fun `a metadata restamp of the current item doesn't reload the receiver`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()

    player.replaceMediaItem(0, item("a").buildUpon().setMediaMetadata(titled("now")).build())
    idle()

    assertEquals(1, remote.player.loads.size)
    assertEquals("now", player.currentMediaItem?.mediaMetadata?.title)
  }

  @Test
  fun `removing the current item plays the one in its place`() {
    playLocally(listOf("a", "b"))
    remote.startSession()
    idle()

    player.removeMediaItem(0)
    idle()

    assertEquals(listOf("a", "b"), remote.player.loads.map { it.item.mediaId })
  }

  @Test
  fun `a new queue plays its start item from its start position`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()

    player.setMediaItems(items("x", "y"), 1, 2_000)
    idle()

    assertEquals(Triple("y", 2_000L, true), remote.player.loads.last().short())
    assertEquals(listOf("x", "y"), queue())
  }

  @Test
  fun `a seek within the playing item seeks the receiver`() {
    playLocally(listOf("a", "b"))
    remote.startSession()
    idle()

    player.seekTo(30_000)
    idle()

    assertEquals(listOf(30_000L), remote.player.seeks)
    assertEquals(1, remote.player.loads.size)
  }

  @Test
  fun `a skip loads the next item`() {
    playLocally(listOf("a", "b"))
    remote.startSession()
    idle()

    player.seekToNextMediaItem()
    idle()

    assertEquals(listOf("a", "b"), remote.player.loads.map { it.item.mediaId })
    assertEquals(1, player.currentMediaItemIndex)
  }

  @Test
  fun `a receiver's failure is the player error until prepare loads the item again`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    remote.player.positionMs = 12_000
    remote.player.update()
    remote.fail()
    idle()

    assertEquals(PlaybackException.ERROR_CODE_REMOTE_ERROR, player.playerError?.errorCode)
    assertEquals(MediaPlayer.STATE_IDLE, player.playbackState)

    player.prepare()
    idle()
    assertNull(player.playerError)
    assertEquals(Triple("a", 12_000L, true), remote.player.loads.last().short())
  }

  @Test
  fun `an item that doesn't resolve is the player error`() {
    unresolvable += "b"
    playLocally(listOf("a", "b"))
    remote.startSession()
    idle()

    player.seekToNextMediaItem()
    idle()

    assertEquals(PlaybackException.ERROR_CODE_REMOTE_ERROR, player.playerError?.errorCode)
    assertEquals(1, remote.player.loads.size)
  }

  @Test
  fun `a pause after a failure still reaches the receiver`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    remote.fail()
    idle()

    player.pause()
    idle()

    assertFalse(remote.player.playWhenReady)
  }

  @Test
  fun `a pause while the next item resolves reaches the receiver, and the item loads paused`() {
    playLocally(listOf("a", "b"))
    remote.startSession()
    idle()
    gate = CompletableDeferred()
    player.seekToNextMediaItem()
    idle()

    player.pause()
    idle()
    assertFalse(remote.player.playWhenReady)

    gate!!.complete(Unit)
    idle()
    assertEquals(Triple("b", C.TIME_UNSET, false), remote.player.loads.last().short())
  }

  @Test
  fun `a play while the next item resolves doesn't play the last one`() {
    playLocally(listOf("a", "b"))
    remote.startSession()
    idle()
    player.pause()
    gate = CompletableDeferred()
    player.seekToNextMediaItem()
    idle()

    player.play()
    idle()
    assertFalse(remote.player.playWhenReady)

    gate!!.complete(Unit)
    idle()
    assertEquals(Triple("b", C.TIME_UNSET, true), remote.player.loads.last().short())
  }

  @Test
  fun `the end of an item doesn't tell the receiver to play what it holds`() {
    playLocally(listOf("a", "b"))
    remote.startSession()
    idle()
    val intentsBefore = remote.player.intents.size

    remote.end()
    idle()

    assertEquals(intentsBefore, remote.player.intents.size)
    assertTrue(remote.player.loads.last().autoplay)
  }

  @Test
  fun `stop stops the receiver, and prepare loads the item again where it was`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    remote.player.positionMs = 8_000
    remote.player.update()

    player.stop()
    idle()
    assertEquals(1, remote.player.stops)
    assertEquals(MediaPlayer.STATE_IDLE, player.playbackState)

    player.prepare()
    idle()
    assertEquals(8_000, remote.player.loads.last().positionMs)
  }

  @Test
  fun `a pause from elsewhere is the play intent`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()

    remote.player.setIntentFromElsewhere(false)
    idle()

    assertFalse(player.playWhenReady)
    remote.endSession()
    idle()
    assertFalse(local.playWhenReady)
  }

  @Test
  fun `a receiver still reporting the last item shows the sent one buffering`() {
    playLocally(listOf("a", "b"))
    remote.startSession()
    idle()
    remote.player.lagging = true

    player.seekToNextMediaItem()
    idle()

    assertEquals("b", player.currentMediaItem?.mediaId)
    assertEquals(MediaPlayer.STATE_BUFFERING, player.playbackState)

    remote.player.catchUp()
    idle()
    assertEquals(MediaPlayer.STATE_READY, player.playbackState)
  }

  @Test
  fun `the volume is the receiver's device volume`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()

    player.volume = 0.5f
    idle()

    assertEquals(FakeReceiver.MAX_VOLUME / 2, remote.player.volume)
    assertEquals(0.5f, player.volume)
  }

  @Test
  fun `another remote's session moves playback straight there`() {
    playLocally(listOf("a", "b"), index = 1)
    remote.startSession()
    idle()
    remote.player.positionMs = 6_000
    remote.player.update()

    other.startSession()
    remote.endSession()
    idle()

    assertEquals(Triple("b", 6_000L, true), other.player.loads.single().short())
    assertEquals(1, local.stops)
    assertEquals(1, local.prepares)
  }

  @Test
  fun `a new session of the playing remote takes over from the last`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    val first = remote.player
    first.positionMs = 9_000
    first.update()

    remote.startSession()
    idle()

    assertEquals(1, first.stops)
    assertEquals(Triple("a", 9_000L, true), remote.player.loads.single().short())
    assertTrue(player.isRemote)
  }

  @Test
  fun `a remote that isn't playing does not fail playback`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    other.sessionUp()

    other.fail()
    idle()

    assertNull(player.playerError)
  }

  @Test
  fun `a session ending while its handover resolves leaves the next remote to take over`() {
    playLocally(listOf("a"))
    other.sessionUp()
    gate = CompletableDeferred()
    remote.startSession()
    idle()

    remote.endSession()
    gate!!.complete(Unit)
    idle()

    assertEquals(1, other.player.loads.size)
    assertTrue(player.isRemote)
  }

  @Test
  fun `a remote turned off is released, and one that stays keeps playing`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()

    player.setRemotes(listOf(remote))
    idle()

    assertTrue(other.released)
    assertFalse(remote.released)
    assertTrue(player.isRemote)
    assertEquals(1, remote.player.loads.size)
  }

  @Test
  fun `turning off the remote that plays brings playback back before letting it go`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    val receiver = remote.player

    player.setRemotes(listOf(other))
    idle()

    assertFalse(player.isRemote)
    assertTrue(remote.released)
    assertEquals(1, receiver.stops)
    assertEquals(listOf("a"), local.items.map { it.mediaId })
    assertTrue(local.playWhenReady)
  }

  @Test
  fun `an item that doesn't resolve at the handover is the player error at once`() {
    unresolvable += "a"
    playLocally(listOf("a"))
    val errors = mutableListOf<PlaybackException>()
    player.addListener(
      object : MediaPlayer.Listener {
        override fun onPlayerError(error: PlaybackException) {
          errors += error
        }
      }
    )

    remote.startSession()
    idle()

    assertEquals(listOf(PlaybackException.ERROR_CODE_REMOTE_ERROR), errors.map { it.errorCode })
  }

  @Test
  fun `an end reported before the receiver holds the item sent is the last item's`() {
    playLocally(listOf("a", "b", "c"))
    remote.startSession()
    idle()
    remote.player.lagging = true
    player.seekToNextMediaItem()
    idle()

    remote.end()
    idle()

    assertEquals(listOf("a", "b"), remote.player.loads.map { it.item.mediaId })
    assertEquals(1, player.currentMediaItemIndex)
  }

  @Test
  fun `an item the receiver doesn't start in time is the player error`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    remote.player.lagging = true
    player.seekToDefaultPosition()
    idle()

    shadowOf(looper).idleFor(Duration.ofSeconds(31))

    assertEquals(PlaybackException.ERROR_CODE_REMOTE_ERROR, player.playerError?.errorCode)
  }

  @Test
  fun `an item the receiver started is not timed out`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    remote.playing()

    shadowOf(looper).idleFor(Duration.ofSeconds(31))

    assertNull(player.playerError)
  }

  @Test
  fun `an item that started and then buffers is not timed out`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    remote.playing()

    remote.player.readiness = MediaPlayer.STATE_BUFFERING
    remote.player.update()
    shadowOf(looper).idleFor(Duration.ofSeconds(31))

    assertNull(player.playerError)
  }

  @Test
  fun `a play the receiver doesn't start in time is the player error`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    player.pause()
    remote.player.readiness = MediaPlayer.STATE_BUFFERING
    remote.player.update()
    idle()

    player.play()
    shadowOf(looper).idleFor(Duration.ofSeconds(31))

    assertEquals(PlaybackException.ERROR_CODE_REMOTE_ERROR, player.playerError?.errorCode)
  }

  @Test
  fun `a play after a paused handover that the receiver never reports playing is the player error`() {
    local.setMediaItems(items("a"))
    local.prepare()
    idle()
    remote.startSession()
    idle()
    shadowOf(looper).idleFor(Duration.ofSeconds(31))
    assertNull(player.playerError)

    player.play()
    shadowOf(looper).idleFor(Duration.ofSeconds(31))

    assertEquals(PlaybackException.ERROR_CODE_REMOTE_ERROR, player.playerError?.errorCode)
  }

  @Test
  fun `a play after a paused handover that the receiver reports playing is not timed out`() {
    local.setMediaItems(items("a"))
    local.prepare()
    idle()
    remote.startSession()
    idle()

    player.play()
    remote.playing()
    shadowOf(looper).idleFor(Duration.ofSeconds(31))

    assertNull(player.playerError)
  }

  @Test
  fun `a playing report before the receiver shows any item counts for the item sent`() {
    playLocally(listOf("a", "b"))
    remote.startSession()
    idle()
    remote.playing()
    remote.player.lagging = true
    player.seekToNextMediaItem()
    idle()

    remote.player.item = null
    remote.player.update()
    remote.playing()
    remote.player.catchUp()
    shadowOf(looper).idleFor(Duration.ofSeconds(31))

    assertNull(player.playerError)
  }

  @Test
  fun `a playing report while the receiver shows the last item doesn't count for the item sent`() {
    playLocally(listOf("a", "b"))
    remote.startSession()
    idle()
    remote.playing()
    remote.player.lagging = true
    player.seekToNextMediaItem()
    idle()

    remote.playing()
    remote.player.catchUp()
    shadowOf(looper).idleFor(Duration.ofSeconds(31))

    assertEquals(PlaybackException.ERROR_CODE_REMOTE_ERROR, player.playerError?.errorCode)
  }

  @Test
  fun `removing the current item when it is the last ends playback`() {
    playLocally(listOf("a", "b"), index = 1)
    remote.startSession()
    idle()

    player.removeMediaItem(1)
    idle()

    assertEquals(MediaPlayer.STATE_ENDED, player.playbackState)
    assertEquals(listOf("b"), remote.player.loads.map { it.item.mediaId })
    assertEquals(1, remote.player.stops)
  }

  @Test
  fun `removing the current item when it is the last goes back to the first under repeat all`() {
    playLocally(listOf("a", "b"), index = 1)
    local.repeatMode = MediaPlayer.REPEAT_MODE_ALL
    remote.startSession()
    idle()

    player.removeMediaItem(1)
    idle()

    assertEquals(listOf("b", "a"), remote.player.loads.map { it.item.mediaId })
    assertEquals(0, player.currentMediaItemIndex)
  }

  @Test
  fun `the phone lets go of its queue while remote`() {
    playLocally(listOf("a", "b"))

    remote.startSession()
    idle()

    assertTrue(local.items.isEmpty())
  }

  @Test
  fun `the last item's end stands where the receiver ended it`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    remote.player.positionMs = 200_000
    remote.player.update()

    remote.end()
    idle()

    assertEquals(MediaPlayer.STATE_ENDED, player.playbackState)
    assertEquals(200_000, player.currentPosition)
  }

  @Test
  fun `a title for the outgoing item while the next one resolves never shows on it`() {
    playLocally(listOf("a", "b"))
    remote.startSession()
    idle()
    remote.player.title("A 1")
    idle()
    val seen = transitionsAndTitles()
    gate = CompletableDeferred()

    player.seekToNextMediaItem()
    idle()
    remote.player.title("A 2")
    idle()
    gate!!.complete(Unit)
    idle()
    remote.player.title("B 1")
    idle()

    assertEquals(listOf("b", "B 1"), seen)
  }

  @Test
  fun `a new load of the same stream shows its title again`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    remote.player.title("Song")
    idle()
    val seen = transitionsAndTitles()

    player.setMediaItems(items("a"))
    idle()
    remote.player.title("Song")
    idle()

    assertEquals(listOf("a", "Song"), seen)
  }

  @Test
  fun `a play after a local stop shows the stream's title again`() {
    playLocally(listOf("a"))
    local.title("Song")
    idle()
    val seen = transitionsAndTitles()

    player.stop()
    player.prepare()
    idle()
    local.title("Song")
    idle()

    assertEquals(listOf("Song"), seen)
  }

  @Test
  fun `each title the phone sends after a stop goes out, though it equals the last`() {
    playLocally(listOf("a"))
    local.title("Song")
    idle()
    val seen = transitionsAndTitles()

    player.stop()
    idle()
    // A title that runs after the stop: passed on here, and dropped by the player's listener.
    local.title("Song")
    idle()
    player.prepare()
    idle()
    local.title("Song")
    idle()

    assertEquals(listOf("Song", "Song"), seen)
  }

  @Test
  fun `a title after the phone reconnects without a stop goes out, though it equals the last`() {
    playLocally(listOf("a"))
    local.title("Song")
    idle()
    val seen = transitionsAndTitles()

    // As the retry loop reconnects: on the phone, not through this player.
    local.stop()
    local.prepare()
    idle()
    local.title("Song")
    idle()

    assertEquals(listOf("Song"), seen)
  }

  @Test
  fun `release brings playback back and lets every remote go`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()

    player.release()
    idle()

    assertEquals(listOf("a"), local.items.map { it.mediaId })
    assertTrue(remote.released)
    assertTrue(other.released)
  }

  @Test
  fun `release while a handover resolves keeps playback local`() {
    playLocally(listOf("a"))
    gate = CompletableDeferred()
    remote.startSession()
    idle()

    player.release()
    gate!!.complete(Unit)
    idle()

    assertEquals(0, local.stops)
    assertTrue(remote.player.loads.isEmpty())
  }

  private fun playLocally(ids: List<String>, index: Int = 0, positionMs: Long = 0) {
    local.setMediaItems(items(*ids.toTypedArray()), index, positionMs)
    local.playWhenReady = true
    local.prepare()
    idle()
  }

  /** The id of each item transitioned to, and each stream title, in the order the player tells. */
  private fun transitionsAndTitles(): List<String> {
    val seen = mutableListOf<String>()
    player.addListener(
      object : MediaPlayer.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
          seen += mediaItem?.mediaId.orEmpty()
        }

        override fun onMetadata(metadata: Metadata) {
          seen += (metadata[0] as IcyInfo).title.orEmpty()
        }
      }
    )
    return seen
  }

  private fun queue() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

  private fun SentLoad.short() = Triple(item.mediaId, positionMs, autoplay)

  private fun items(vararg ids: String) = ids.map(::item)

  private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://s/$id").build()

  private fun titled(title: String) =
    androidx.media3.common.MediaMetadata.Builder().setTitle(title).build()

  private fun idle() = shadowOf(looper).idle()

  private companion object {
    val LIVE: MediaItem.LiveConfiguration =
      MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(5_000).build()
  }
}
