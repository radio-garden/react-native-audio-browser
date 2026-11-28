package com.audiobrowser.player

import android.content.Context
import androidx.core.content.edit
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.margelo.nitro.audiobrowser.RepeatMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlaybackStateStoreQueueTest {
  private val context = RuntimeEnvironment.getApplication()
  private val player = Player(context).apply { setup(PlayerSetupOptions()) }

  @After fun release() = player.exoPlayer.release()

  private fun track(id: String, disabled: Boolean? = null) =
    TestFixtures.track(title = id, id = id, src = "https://s/$id.mp3", disabled = disabled)

  @Test
  fun `a save keeps the queue and the current index`() {
    player.setQueue(arrayOf(track("a"), track("b"), track("c")), startIndex = 1, prepare = false)

    player.playbackStateStore.save()
    val saved = player.playbackStateStore.get()

    assertEquals("b", saved?.track?.id)
    assertEquals(listOf("a", "b", "c"), saved?.queue?.map { it.id })
    assertEquals(1, saved?.queueIndex)
    assertEquals(listOf("a", "b", "c"), saved?.storedQueue?.first?.map { it.id })
  }

  @Test
  fun `a save from before the queue was kept still loads`() {
    player.setQueue(arrayOf(track("a"), track("b")), startIndex = 1, prepare = false)
    player.playbackStateStore.save()
    context.getSharedPreferences("audio_browser_playback_state_v3", Context.MODE_PRIVATE).edit {
      remove("queue")
      remove("queue_index")
    }

    val saved = player.playbackStateStore.get()

    assertEquals("b", saved?.track?.id)
    assertNull(saved?.queue)
    assertNull(saved?.storedQueue)
  }

  private val prefs
    get() = context.getSharedPreferences("audio_browser_playback_state_v3", Context.MODE_PRIVATE)

  @Test
  fun `a position save leaves the stored queue alone`() {
    player.setQueue(arrayOf(track("a"), track("b")), prepare = false)
    prefs.edit { putString("queue", "untouched") }

    player.playbackStateStore.save()

    assertEquals("untouched", prefs.getString("queue", null))
  }

  @Test
  fun `a queue change writes the queue without a save`() {
    player.setQueue(arrayOf(track("a"), track("b")), prepare = false)
    player.add(arrayOf(track("c")))

    val saved = player.playbackStateStore.get()

    assertEquals(listOf("a", "b", "c"), saved?.queue?.map { it.id })
    assertEquals(0, saved?.queueIndex)
  }

  @Test
  fun `a malformed queue loads as no queue, keeping the track`() {
    player.setQueue(arrayOf(track("a"), track("b")), startIndex = 1, prepare = false)
    player.playbackStateStore.save()
    prefs.edit { putString("queue", "{not json") }

    val saved = player.playbackStateStore.get()

    assertEquals("b", saved?.track?.id)
    assertNull(saved?.queue)
  }

  @Test
  fun `a clear keeps the settings`() {
    player.playbackStateStore.repeatMode = RepeatMode.QUEUE
    player.setQueue(arrayOf(track("a")), prepare = false)
    player.playbackStateStore.save()

    PlaybackStateStore.clear(context)

    assertNull(player.playbackStateStore.get())
    player.playbackStateStore.save()
    assertEquals(RepeatMode.QUEUE, player.playbackStateStore.get()?.repeatMode)
  }

  @Test
  fun `a disabled track is no session to restore`() {
    player.setQueue(arrayOf(track("a", disabled = true)), prepare = false)
    player.playbackStateStore.save()

    assertNull(player.playbackStateStore.session())
  }
}
