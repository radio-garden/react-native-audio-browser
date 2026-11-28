package com.audiobrowser.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.browser.ExpandedQueue
import com.audiobrowser.browser.RestoreExpansion
import com.audiobrowser.model.PlayerSetupOptions
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.PlaybackPlayWhenReadyChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.RepeatMode
import com.margelo.nitro.audiobrowser.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

private fun station(id: String) =
  TestFixtures.track(title = id, id = id, src = "https://example.com/$id.mp3", live = true)

private fun session(track: Track, queue: List<Track>? = null, index: Int? = null) =
  PlaybackStateStore.PersistedState(
    track = track,
    positionMs = C.TIME_UNSET,
    repeatMode = RepeatMode.QUEUE,
    shuffleEnabled = false,
    playbackSpeed = 1f,
    queue = queue,
    queueIndex = index,
  )

private fun Track.at(path: String) = copy(path = path)

@RunWith(RobolectricTestRunner::class)
class PlayerRestoreTest {
  private val player =
    Player(RuntimeEnvironment.getApplication()).apply { setup(PlayerSetupOptions()) }

  @After fun release() = player.exoPlayer.release()

  private val ids
    get() = player.tracks.map { it.id }

  private val engineState
    get() = player.exoPlayer.playbackState

  private fun idle() = shadowOf(Looper.getMainLooper()).idle()

  @Test
  fun `restores the stored queue at its station`() {
    val queue = listOf(station("a"), station("b"), station("c"))

    player.restore(session(queue[1], queue, 1), expanded = null)

    assertEquals(listOf("a", "b", "c"), ids)
    assertEquals(1, player.currentIndex)
    assertFalse(player.playWhenReady)
  }

  @Test
  fun `a paused restore reports paused`() {
    player.restore(session(station("a"), listOf(station("a")), 0), expanded = null)
    idle()

    assertEquals(PlaybackState.PAUSED, player.playbackState)
  }

  @Test
  fun `a paused restore opens no stream and shows the session an idle player`() {
    val queue = listOf(station("a"), station("b"))

    player.restore(session(queue[0], queue, 0), expanded = null)
    idle()

    assertEquals(MediaPlayer.STATE_IDLE, engineState)
    // Media3 shows no notification and starts no foreground service for an idle session player
    // that was never prepared.
    assertEquals(MediaPlayer.STATE_IDLE, player.forwardingPlayer.playbackState)
    assertFalse(player.forwardingPlayer.playWhenReady)
    assertFalse(player.getPlayingState().playing || player.getPlayingState().buffering)
  }

  @Test
  fun `play after a restore connects the station`() {
    player.restore(session(station("a"), listOf(station("a")), 0), expanded = null)
    idle()

    player.play()

    assertNotEquals(MediaPlayer.STATE_IDLE, engineState)
    assertNotEquals(MediaPlayer.STATE_IDLE, player.forwardingPlayer.playbackState)
    assertFalse(player.restoredUnprepared)
    assertTrue(player.playWhenReady)
  }

  @Test
  fun `a play sent while the restore ran plays the restored station`() {
    player.play()

    player.restore(session(station("a"), listOf(station("a")), 0), expanded = null)

    assertTrue(player.playWhenReady)
    assertNotEquals(MediaPlayer.STATE_IDLE, engineState)
  }

  @Test
  fun `an expanded queue wins over the stored one`() {
    val expanded = ExpandedQueue(arrayOf(station("x"), station("b"), station("y")), 1, "/page")

    player.restore(session(station("b"), listOf(station("b")), 0), expanded)

    assertEquals(listOf("x", "b", "y"), ids)
    assertEquals(1, player.currentIndex)
    assertEquals("/page", player.queueSourcePath)
  }

  @Test
  fun `a stored queue whose index is not the station restores the station alone`() {
    player.restore(session(station("b"), listOf(station("a"), station("c")), 1), expanded = null)

    assertEquals(listOf("b"), ids)
  }

  @Test
  fun `a session saved without a queue restores the station alone`() {
    player.restore(session(station("b")), expanded = null)

    assertEquals(listOf("b"), ids)
    assertEquals(RepeatMode.QUEUE, player.repeatMode)
  }

  private fun run(
    saved: PlaybackStateStore.PersistedState,
    isCurrent: Boolean = true,
    expand: suspend (PlaybackStateStore.PersistedState) -> RestoreExpansion = {
      RestoreExpansion.Unavailable
    },
  ) = runBlocking {
    PlaybackRestore.run(
      player,
      saved,
      player.queueVersion,
      PlaybackStateStore.clearGeneration,
      expand,
    ) {
      isCurrent
    }
  }

  /** Saves [queue] at [index] as the persisted session, and empties the player. */
  private fun persist(queue: List<Track>, index: Int = 0) {
    player.setQueue(queue.toTypedArray(), startIndex = index, prepare = false)
    player.playbackStateStore.save()
    player.clear()
    idle()
  }

  /**
   * As AudioBrowser.applySetupState sets up: the intent and repeat mode staged, then the restore.
   */
  private fun setUp(
    restore: Boolean = true,
    playWhenReady: Boolean? = true,
    repeatMode: RepeatMode? = null,
    expand: suspend (PlaybackStateStore.PersistedState) -> RestoreExpansion = {
      RestoreExpansion.Unavailable
    },
  ): Boolean? {
    val generation = PlaybackStateStore.clearGeneration
    val session = PlaybackRestore.sessionForSetup(player, restore, repeatMode)
    val intent = PlaybackRestore.setupIntent(playWhenReady, session)
    repeatMode?.let { player.repeatMode = it }
    intent?.let { player.applySetupPlayWhenReady(it) }
    session?.let { PlaybackRestore.start(player, it, generation, MainScope(), expand) { true } }
    return intent
  }

  @Test
  fun `a contextual path is expanded afresh`() {
    val saved = station("b").at("/page?__trackId=b")
    var asked: String? = null

    run(session(saved, listOf(saved), 0)) { state ->
      asked = state.track.path
      RestoreExpansion.Queue(ExpandedQueue(arrayOf(station("a"), station("b")), 1, "/page"))
    }

    assertEquals("/page?__trackId=b", asked)
    assertEquals(listOf("a", "b"), ids)
    assertEquals(1, player.currentIndex)
  }

  @Test
  fun `a failed expansion restores the stored queue`() {
    val saved = station("b").at("/page?__trackId=b")

    run(session(saved, listOf(station("a"), saved), 1))

    assertEquals(listOf("a", "b"), ids)
    assertEquals(1, player.currentIndex)
  }

  @Test
  fun `a play during the expansion plays the restored station`() {
    val saved = station("b").at("/page?__trackId=b")

    run(session(saved, listOf(saved), 0)) {
      player.play()
      RestoreExpansion.Unavailable
    }

    assertTrue(player.playWhenReady)
    assertEquals(listOf("b"), ids)
    assertNotEquals(MediaPlayer.STATE_IDLE, engineState)
  }

  @Test
  fun `a queue set during the expansion is kept`() {
    val saved = station("b").at("/page?__trackId=b")

    run(session(saved, listOf(saved), 0)) {
      player.setQueue(arrayOf(station("picked")))
      RestoreExpansion.Queue(ExpandedQueue(arrayOf(station("a"), station("b")), 1, "/page"))
    }

    assertEquals(listOf("picked"), ids)
  }

  @Test
  fun `a track its page now disables restores nothing, not even the stored queue`() {
    val saved = station("b").at("/page?__trackId=b")

    run(session(saved, listOf(station("a"), saved), 1)) { RestoreExpansion.Refused }

    assertTrue(player.isEmpty)
  }

  @Test
  fun `a restore onto an emptied engine loads nothing until a play`() {
    // A queue played and cleared, as a service that outlived a JS reload holds it.
    player.setQueue(arrayOf(station("a")))
    player.play()
    player.playbackStateStore.save()
    player.clear()
    idle()

    setUp()
    idle()

    assertEquals(listOf("a"), ids)
    assertEquals(MediaPlayer.STATE_IDLE, engineState)
    assertEquals(MediaPlayer.STATE_IDLE, player.forwardingPlayer.playbackState)
    assertEquals(PlaybackState.PAUSED, player.playbackState)

    player.play()

    assertNotEquals(MediaPlayer.STATE_IDLE, engineState)
    assertFalse(player.restoredUnprepared)
  }

  @Test
  fun `a setup asking to play restores paused, with no intent raised on the way`() {
    player.setQueue(arrayOf(station("a"), station("b")), startIndex = 1, prepare = false)
    player.playbackStateStore.save()
    player.clear()
    idle()
    val callbacks = mock(Callbacks::class.java)
    player.setCallbacks(callbacks)

    val intent = setUp()
    idle()

    assertEquals(false, intent)
    assertEquals(listOf("a", "b"), ids)
    assertEquals(1, player.currentIndex)
    assertFalse(player.playWhenReady)
    assertEquals(PlaybackState.PAUSED, player.playbackState)
    assertEquals(MediaPlayer.STATE_IDLE, engineState)
    verify(callbacks, never())
      .onPlaybackPlayWhenReadyChanged(PlaybackPlayWhenReadyChangedEvent(true))
    // One settled state, not an intent raised and dropped.
    verify(callbacks, times(1)).onPlaybackChanged(Playback(PlaybackState.PAUSED, null))
  }

  @Test
  fun `a play before the restore starts plays the restored queue`() {
    player.setQueue(arrayOf(station("a")), prepare = false)
    player.playbackStateStore.save()
    player.clear()
    idle()

    val intent = setUp()
    // A media button, before the launched restore first runs.
    player.play()
    idle()

    assertEquals(listOf("a"), ids)
    assertTrue(player.playWhenReady)
    assertNotEquals(MediaPlayer.STATE_IDLE, engineState)
  }

  @Test
  fun `a setup that does not ask to restore keeps its intent`() {
    player.setQueue(arrayOf(station("a")), prepare = false)
    player.playbackStateStore.save()
    player.clear()

    val intent = setUp(restore = false)
    idle()

    assertEquals(true, intent)
    assertTrue(player.isEmpty)
  }

  @Test
  fun `a player that has a queue is left alone`() {
    persist(listOf(station("a")))
    player.setQueue(arrayOf(station("current")), prepare = false)

    assertEquals(true, setUp())
    idle()

    assertEquals(listOf("current"), ids)
  }

  @Test
  fun `a player replaced during the expansion is left alone`() {
    run(session(station("a"), listOf(station("a")), 0), isCurrent = false)

    assertTrue(player.isEmpty)
  }

  @Test
  fun `a clear while the restore expands restores nothing and keeps the store empty`() {
    persist(listOf(station("a"), station("b")))
    val expansion = CompletableDeferred<RestoreExpansion>()

    setUp { expansion.await() }
    idle()
    PlaybackStateStore.clear(RuntimeEnvironment.getApplication())
    expansion.complete(RestoreExpansion.Unavailable)
    idle()

    assertTrue(player.isEmpty)
    assertNull(player.playbackStateStore.get())
    assertNull(
      RuntimeEnvironment.getApplication()
        .getSharedPreferences("audio_browser_playback_state_v3", Context.MODE_PRIVATE)
        .getString("queue", null)
    )
  }

  @Test
  fun `a repeat mode the setup sets wins over the stored one`() {
    player.playbackStateStore.repeatMode = RepeatMode.QUEUE
    persist(listOf(station("a")))

    setUp(repeatMode = RepeatMode.TRACK)
    idle()

    assertEquals(listOf("a"), ids)
    assertEquals(RepeatMode.TRACK, player.repeatMode)
  }

  @Test
  fun `a clear between reading the session and starting the restore restores nothing`() {
    persist(listOf(station("a")))

    val generation = PlaybackStateStore.clearGeneration
    val session = PlaybackRestore.sessionForSetup(player, restore = true, repeatMode = null)!!
    PlaybackStateStore.clear(RuntimeEnvironment.getApplication())
    PlaybackRestore.start(
      player,
      session,
      generation,
      MainScope(),
      { RestoreExpansion.Unavailable },
    ) {
      true
    }
    idle()

    assertTrue(player.isEmpty)
    assertNull(player.playbackStateStore.get())
  }

  @Test
  fun `a cleared session is not restored at setup`() {
    persist(listOf(station("a")))

    PlaybackStateStore.clear(RuntimeEnvironment.getApplication())

    assertEquals(true, setUp())
    idle()
    assertTrue(player.isEmpty)
  }

  @Test
  fun `nothing persisted restores nothing and keeps the intent`() {
    assertEquals(true, setUp())
    idle()

    assertTrue(player.isEmpty)
    assertTrue(player.playWhenReady)
  }
}
