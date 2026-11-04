package com.audiobrowser.remote

import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RemoteSwitchingPlayerTest {
  private val looper = Looper.getMainLooper()
  private val scope = MainScope()
  private val local = FakePlayer(looper, DeviceInfo.UNKNOWN)
  private val remote = FakeRemote(looper)
  private val other = FakeRemote(looper)
  private val resolutions = RemoteMediaResolutions()
  private val resolved = mutableListOf<String>()
  private val live = mutableSetOf<String>()

  /** Holds a resolution until completed, for the steps that must wait on one. */
  private var gate: CompletableDeferred<Unit>? = null

  private val player =
    RemoteSwitchingPlayer(
      local,
      listOf(remote, other),
      resolutions,
      resolveMedia = { item ->
        gate?.await()
        resolved += item.mediaId
        RemoteMedia("https://cdn.example/${item.mediaId}", "audio/mpeg", null, item.mediaId in live)
      },
      scope = scope,
    )

  @After fun cancel() = scope.cancel()

  @Test
  fun `a session takes the queue, its position and the play intent to the receiver`() {
    playLocally(listOf("a", "b", "c"), index = 1, positionMs = 5_000)

    remote.startSession()
    idle()

    assertTrue(player.isRemote)
    assertEquals(listOf("a", "b", "c"), remote.player.items.map { it.mediaId })
    assertEquals(1, remote.player.index)
    assertEquals(5_000, remote.player.positionMs)
    assertTrue(remote.player.playWhenReady)
    assertEquals(listOf("a", "b", "c"), resolved)
    assertEquals(1, local.stops)
    assertEquals(1, player.currentMediaItemIndex)
  }

  @Test
  fun `the phone plays on until the receiver's media is resolved`() {
    playLocally(listOf("a"))
    gate = CompletableDeferred()

    remote.startSession()
    idle()

    assertFalse(player.isRemote)
    assertEquals(0, local.stops)
    assertTrue(remote.player.items.isEmpty())

    gate!!.complete(Unit)
    idle()
    assertTrue(player.isRemote)
  }

  @Test
  fun `a live item starts at the receiver's live edge`() {
    live += "a"
    playLocally(listOf("a"), positionMs = 90_000)

    remote.startSession()
    idle()

    assertEquals(0, remote.player.positionMs)
  }

  @Test
  fun `the session ending brings the queue, its position and the play intent back`() {
    playLocally(listOf("a", "b"))
    remote.startSession()
    idle()
    remote.player.index = 1
    remote.player.positionMs = 7_000
    remote.player.update()
    idle()

    remote.endSession()
    idle()

    assertFalse(player.isRemote)
    assertEquals(listOf("a", "b"), local.items.map { it.mediaId })
    assertEquals(1, local.index)
    assertEquals(7_000, local.positionMs)
    assertTrue(local.playWhenReady)
    assertEquals(MediaPlayer.STATE_READY, local.playbackState)
  }

  @Test
  fun `a session ending before the media is resolved leaves playback here`() {
    playLocally(listOf("a"))
    gate = CompletableDeferred()
    remote.startSession()
    idle()

    remote.endSession()
    gate!!.complete(Unit)
    idle()

    assertFalse(player.isRemote)
    assertEquals(0, local.stops)
    assertTrue(remote.player.sent.isEmpty())
  }

  @Test
  fun `a now-playing restamp while casting does not reload the receiver`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    val sent = remote.player.sent.size

    player.replaceMediaItem(0, item("a", title = "Song – Artist"))
    idle()

    assertEquals(sent, remote.player.sent.size)
    assertEquals("Song – Artist", player.currentMediaItem?.mediaMetadata?.title)
  }

  @Test
  fun `a restamp of a new queue still resolving does not reach the receiver`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    gate = CompletableDeferred()

    player.setMediaItems(listOf(item("b")))
    val transitions = mutableListOf<Int>()
    player.addListener(
      object : MediaPlayer.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
          transitions += reason
        }
      }
    )
    player.replaceMediaItem(0, item("b", title = "Song – Artist"))
    idle()
    assertEquals(emptyList<Int>(), transitions)
    gate!!.complete(Unit)
    idle()

    assertEquals(
      listOf(listOf("b")),
      remote.player.sent.drop(1).map { items -> items.map { it.mediaId } },
    )
    assertEquals("Song – Artist", player.currentMediaItem?.mediaMetadata?.title)
  }

  @Test
  fun `items the receiver reports without their data show as sent`() {
    playLocally(listOf("a", "b", "c", "d"))
    remote.startSession()
    idle()

    remote.player.forget(0)
    remote.player.forget(3)
    idle()

    assertEquals(
      listOf("tag-a", "tag-b", "tag-c", "tag-d"),
      (0 until player.mediaItemCount).map { player.getMediaItemAt(it).localConfiguration?.tag },
    )
  }

  @Test
  fun `an earlier queue's item the receiver reports shows as the item sent`() {
    playLocally(listOf("a", "b"))
    remote.startSession()
    idle()

    remote.player.report(0, item("old"))
    idle()

    assertEquals("a", player.getMediaItemAt(0).mediaId)
  }

  @Test
  fun `an item the receiver reports without its tag shows as it was sent`() {
    playLocally(listOf("a", "b"))
    remote.startSession()
    idle()

    remote.player.rebuildWithoutTag(1)
    idle()

    assertEquals("tag-b", player.getMediaItemAt(1).localConfiguration?.tag)
  }

  @Test
  fun `a restamp made while casting comes back with the queue`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    player.replaceMediaItem(0, item("a", title = "Song – Artist"))
    idle()

    remote.endSession()
    idle()

    assertEquals("Song – Artist", local.items.single().mediaMetadata.title)
  }

  @Test
  fun `queue changes while casting reach the receiver resolved and in order`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    gate = CompletableDeferred()

    player.addMediaItem(item("b"))
    player.removeMediaItem(0)
    idle()
    assertEquals(listOf("a"), remote.player.items.map { it.mediaId })

    gate!!.complete(Unit)
    idle()
    assertEquals(listOf("b"), remote.player.items.map { it.mediaId })
    assertTrue(item("b") in resolutions)
  }

  @Test
  fun `a new local engine keeps the session`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()

    val engine = FakePlayer(looper, DeviceInfo.UNKNOWN)
    player.setLocal(engine)
    idle()
    assertTrue(player.isRemote)
    assertFalse(remote.released)

    remote.endSession()
    idle()
    assertSame(engine, player.local)
    assertEquals(listOf("a"), engine.items.map { it.mediaId })
  }

  @Test
  fun `release brings playback back and lets the remote go`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()

    player.release()
    idle()

    assertTrue(remote.released)
    assertEquals(listOf("a"), local.items.map { it.mediaId })
    assertTrue(local.playWhenReady)
  }

  @Test
  fun `a receiver's failure is the player error until prepare loads the queue again`() {
    playLocally(listOf("a", "b"), index = 1)
    remote.startSession()
    idle()
    val sent = remote.player.sent.size

    remote.fail()
    idle()
    assertEquals(PlaybackException.ERROR_CODE_REMOTE_ERROR, player.playerError?.errorCode)
    assertEquals(MediaPlayer.STATE_IDLE, player.playbackState)

    player.prepare()
    idle()
    assertNull(player.playerError)
    assertEquals(sent + 1, remote.player.sent.size)
    assertEquals(listOf("a", "b"), remote.player.items.map { it.mediaId })
    assertEquals(1, remote.player.index)
  }

  @Test
  fun `stop while casting pauses the receiver and keeps its queue`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()

    player.stop()
    idle()

    assertEquals(0, remote.player.stops)
    assertFalse(remote.player.playWhenReady)
    assertEquals(listOf("a"), player.mediaItems())
  }

  @Test
  fun `a change still waiting when the session ends applies once`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    gate = CompletableDeferred()

    player.addMediaItem(item("b"))
    idle()
    remote.endSession()
    gate!!.complete(Unit)
    idle()

    assertEquals(listOf("a", "b"), local.items.map { it.mediaId })
  }

  @Test
  fun `an item added again after its removal shows as sent, not as restamped`() {
    playLocally(listOf("a", "b"))
    remote.startSession()
    idle()
    player.replaceMediaItem(1, item("b", title = "old song"))
    player.removeMediaItem(1)
    idle()

    player.addMediaItem(item("b", title = "b"))
    idle()

    assertEquals("b", player.getMediaItemAt(1).mediaMetadata.title)
  }

  @Test
  fun `a session already up is handed the queue`() {
    val local = FakePlayer(looper, DeviceInfo.UNKNOWN)
    local.setMediaItems(listOf(item("a")))
    local.prepare()
    val remote = FakeRemote(looper).apply { sessionUp() }

    val player = RemoteSwitchingPlayer(local, listOf(remote), resolutions, { null }, scope)
    idle()

    assertTrue(player.isRemote)
    assertEquals(listOf("a"), remote.player.items.map { it.mediaId })
  }

  @Test
  fun `a skip while a new queue resolves lands on the new queue`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    gate = CompletableDeferred()
    val sent = remote.player.sent.size

    player.setMediaItems(listOf(item("b"), item("c"), item("d")))
    player.seekTo(2, 0)
    assertEquals(2, player.currentMediaItemIndex)
    gate!!.complete(Unit)
    idle()

    assertEquals(listOf("b", "c", "d"), remote.player.items.map { it.mediaId })
    assertEquals(2, remote.player.index)
    assertEquals(sent + 1, remote.player.sent.size)
  }

  @Test
  fun `the session ending while a new queue resolves hands back the new queue`() {
    playLocally(listOf("a", "b", "c"), index = 2)
    remote.startSession()
    idle()
    gate = CompletableDeferred()

    player.setMediaItems(listOf(item("d")))
    remote.endSession()
    gate!!.complete(Unit)
    idle()

    assertEquals(listOf("d"), local.items.map { it.mediaId })
    assertEquals(0, local.index)
  }

  @Test
  fun `prepare after a failure while a new queue resolves loads it once`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    remote.fail()
    idle()
    gate = CompletableDeferred()
    val sent = remote.player.sent.size

    player.setMediaItems(listOf(item("b")))
    player.prepare()
    gate!!.complete(Unit)
    idle()

    assertEquals(
      listOf(listOf("b")),
      remote.player.sent.drop(sent).map { items -> items.map { it.mediaId } },
    )
    assertNull(player.playerError)
  }

  @Test
  fun `a receiver that dropped its queue is idle, and prepare loads it again`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()

    remote.player.clearMediaItems()
    idle()
    assertEquals(MediaPlayer.STATE_IDLE, player.playbackState)
    assertEquals(listOf("a"), player.mediaItems())

    player.prepare()
    idle()
    assertEquals(listOf("a"), remote.player.items.map { it.mediaId })
  }

  @Test
  fun `an empty queue while casting stops the receiver`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()

    player.setMediaItems(emptyList())
    idle()

    assertEquals(1, remote.player.stops)
    assertEquals(0, player.mediaItemCount)
  }

  @Test
  fun `the handover never shows an empty queue`() {
    playLocally(listOf("a", "b"))
    val counts = mutableListOf<Int>()
    player.addListener(
      object : MediaPlayer.Listener {
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
          counts += timeline.windowCount
        }
      }
    )

    remote.startSession()
    idle()

    assertTrue(player.isRemote)
    assertFalse(0 in counts)
  }

  @Test
  fun `queue commands stay available while the receiver's own lag`() {
    playLocally(listOf("a", "b"))
    remote.player.commands =
      MediaPlayer.Commands.Builder()
        .addAllCommands()
        .removeAll(
          MediaPlayer.COMMAND_SEEK_TO_NEXT,
          MediaPlayer.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
          MediaPlayer.COMMAND_SEEK_TO_PREVIOUS,
          MediaPlayer.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        .build()
    remote.startSession()
    idle()

    assertTrue(player.isCommandAvailable(MediaPlayer.COMMAND_SEEK_TO_NEXT))
    player.seekToNext()
    idle()
    assertEquals(1, remote.player.index)
  }

  @Test
  fun `a pause from another sender holds through a handback`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()

    remote.player.setIntentFromElsewhere(false)
    idle()
    remote.endSession()
    idle()

    assertFalse(local.playWhenReady)
  }

  @Test
  fun `a load carries the play intent into the receiver's autoplay`() {
    playLocally(listOf("a"))
    player.playWhenReady = false
    idle()

    remote.startSession()
    idle()

    assertEquals(listOf(false), remote.player.intentAtLoads)
  }

  @Test
  fun `the previous item's failure while a new queue resolves is not the new one's`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    gate = CompletableDeferred()

    player.setMediaItems(listOf(item("b")))
    remote.fail()
    gate!!.complete(Unit)
    idle()

    assertNull(player.playerError)
    assertEquals(listOf("b"), remote.player.items.map { it.mediaId })
  }

  @Test
  fun `the remote with a session plays, whichever it is in the list`() {
    playLocally(listOf("a"))

    other.startSession()
    idle()

    assertTrue(player.isRemote)
    assertEquals(listOf("a"), other.player.items.map { it.mediaId })
    assertTrue(remote.player.items.isEmpty())
  }

  @Test
  fun `a remote keeps playing while another starts a session`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()

    other.startSession()
    idle()

    assertEquals(listOf("a"), remote.player.items.map { it.mediaId })
    assertEquals(1, remote.player.sent.size)
    assertTrue(other.player.items.isEmpty())
  }

  @Test
  fun `when the playing remote's session ends, the next one takes the queue through the phone`() {
    playLocally(listOf("a", "b"), index = 1)
    remote.startSession()
    idle()
    other.sessionUp()

    remote.endSession()
    idle()

    assertEquals(listOf("a", "b"), other.player.items.map { it.mediaId })
    assertEquals(1, other.player.index)
    assertTrue(other.player.playWhenReady)
    assertEquals(1, remote.player.stops)
    assertEquals(2, local.stops)
  }

  @Test
  fun `a remote that isn't playing does not fail playback`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()

    other.fail()
    idle()

    assertNull(player.playerError)
  }

  @Test
  fun `a receiver's failure after a handback that cut a load short still reaches the app`() {
    playLocally(listOf("a"))
    remote.startSession()
    idle()
    gate = CompletableDeferred()
    player.setMediaItems(listOf(item("b")))
    idle()
    remote.endSession()
    idle()
    gate!!.complete(Unit)
    player.stop()
    idle()

    remote.startSession()
    idle()
    remote.fail()
    idle()

    assertEquals(PlaybackException.ERROR_CODE_REMOTE_ERROR, player.playerError?.errorCode)
  }

  @Test
  fun `a session ending while its handover resolves leaves the next remote to take over`() {
    playLocally(listOf("a"))
    gate = CompletableDeferred()
    remote.startSession()
    idle()
    other.startSession()
    remote.endSession()
    gate!!.complete(Unit)
    idle()

    assertTrue(remote.player.items.isEmpty())
    assertEquals(listOf("a"), other.player.items.map { it.mediaId })
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

    assertTrue(remote.player.items.isEmpty())
    assertEquals(0, local.stops)
    assertTrue(remote.released)
  }

  @Test
  fun `release lets go of every remote`() {
    player.release()

    assertTrue(remote.released)
    assertTrue(other.released)
  }

  private fun playLocally(ids: List<String>, index: Int = 0, positionMs: Long = 0) {
    player.setMediaItems(ids.map { item(it) }, index, positionMs)
    player.prepare()
    player.playWhenReady = true
    idle()
  }

  private fun MediaPlayer.mediaItems() = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }

  private fun item(id: String, title: String = id) =
    MediaItem.Builder()
      .setMediaId(id)
      .setUri("/listen/$id")
      .setTag("tag-$id")
      .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
      .build()

  private fun idle() = shadowOf(looper).idle()
}
