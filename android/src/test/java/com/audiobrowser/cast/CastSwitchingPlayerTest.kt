package com.audiobrowser.cast

import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player as MediaPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CastSwitchingPlayerTest {
  private val looper = Looper.getMainLooper()
  private val scope = MainScope()
  private val local = FakePlayer(looper, DeviceInfo.UNKNOWN)
  private val remote = FakeCastRemote(looper)
  private val resolutions = CastMediaResolutions()
  private val resolved = mutableListOf<String>()

  /** Holds a resolution until completed, for the steps that must wait on one. */
  private var gate: CompletableDeferred<Unit>? = null

  private val player =
    CastSwitchingPlayer(
      local,
      remote,
      resolutions,
      resolveMedia = { item ->
        gate?.await()
        resolved += item.mediaId
        CastMedia("https://cdn.example/${item.mediaId}", "audio/mpeg", null, live = false)
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
    playLocally(listOf("a"), positionMs = 90_000)
    resolutions[local.items[0]] =
      CastMedia("https://cdn.example/a", "audio/mpeg", null, live = true)

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

  private fun playLocally(ids: List<String>, index: Int = 0, positionMs: Long = 0) {
    player.setMediaItems(ids.map { item(it) }, index, positionMs)
    player.prepare()
    player.playWhenReady = true
    idle()
  }

  private fun item(id: String, title: String = id) =
    MediaItem.Builder()
      .setMediaId(id)
      .setUri("/listen/$id")
      .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
      .build()

  private fun idle() = shadowOf(looper).idle()
}
