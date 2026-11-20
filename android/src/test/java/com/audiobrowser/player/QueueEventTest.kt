package com.audiobrowser.player

import android.os.Looper
import androidx.media3.session.MediaSession
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.browser.FavoriteChange
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.remote.FakeRemote
import com.audiobrowser.remote.RemoteSwitchingPlayer
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.NowPlayingUpdate
import com.margelo.nitro.audiobrowser.RemoteOutputsOptions
import com.margelo.nitro.audiobrowser.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * What JS hears of an in-place replace of queued items. The engine reports it as a playlist change,
 * but [RemoteSwitchingPlayer] reports it as a source update, playing locally or casting, so each
 * session player runs every test.
 */
abstract class QueueEventTest {
  /** Null for the bare engine as the session player. */
  abstract val remoteOutputs: RemoteOutputsOptions?

  /** Runs once the queue is set, before the test. */
  open fun route() {}

  protected val looper: Looper = Looper.getMainLooper()
  internal val castRemote = FakeRemote(looper)
  private val app = RuntimeEnvironment.getApplication()
  internal val player =
    Player(app, castRemote = { _, _ -> castRemote }, sonosRemote = { _, _ -> FakeRemote(looper) })
  private val callbacks = mock(Callbacks::class.java)
  private lateinit var session: MediaSession
  private val a = TestFixtures.track(title = "a", src = "https://s/a.mp3", favorited = false)
  private val b = TestFixtures.track(title = "b", src = "https://s/b.mp3", favorited = false)

  @Before
  fun setUp() {
    player.setup(PlayerSetupOptions(remoteOutputs = remoteOutputs))
    assertEquals(remoteOutputs != null, player.sessionPlayer is RemoteSwitchingPlayer)
    session = MediaSession.Builder(app, player.sessionPlayer).build()
    player.setMediaSession(session)
    player.applyOptions(
      PlayerUpdateOptions().let { it.copy(capabilities = it.capabilities.copy(favorite = true)) }
    )
    player.setCallbacks(callbacks)
    player.setQueue(arrayOf(a, b))
    idle()
    route()
    clearInvocations(callbacks)
  }

  @After
  fun release() {
    session.release()
    player.destroy()
    TrackFactory.favoriteEnabled = false
  }

  protected fun idle() = shadowOf(looper).idle()

  private fun calls(name: String) =
    mockingDetails(callbacks).invocations.filter { it.method.name == name }

  /** Each queue JS was sent since the last check, as its tracks' titles and `favorited`. */
  private fun queueEvents(): List<List<Pair<String, Boolean?>>> {
    val queues =
      calls("onPlaybackQueueChanged").map { call ->
        (call.arguments[0] as Array<*>).map { (it as Track).let { t -> t.title to t.favorited } }
      }
    clearInvocations(callbacks)
    return queues
  }

  private fun expectOneQueue(vararg tracks: Pair<String, Boolean?>, change: () -> Unit) {
    val version = player.queueVersion
    change()
    idle()
    assertEquals(listOf(tracks.toList()), queueEvents())
    assertNotEquals(version, player.queueVersion)
  }

  @Test
  fun `adding a favorite sends the restamped queue once and moves the queue version`() =
    expectOneQueue("a" to true, "b" to false) {
      player.applyFavoriteChange(FavoriteChange.between(emptySet(), setOf(a.src!!))!!)
    }

  @Test
  fun `removing a favorite sends the restamped queue once and moves the queue version`() {
    player.applyFavoriteChange(FavoriteChange.between(emptySet(), setOf(a.src!!))!!)
    idle()
    queueEvents()

    expectOneQueue("a" to false, "b" to false) {
      player.applyFavoriteChange(FavoriteChange.between(setOf(a.src!!), emptySet())!!)
    }
  }

  @Test
  fun `a toggle sends the restamped queue once and moves the queue version`() =
    expectOneQueue("a" to true, "b" to false) { player.setActiveTrackFavorited(true) }

  @Test
  fun `a track replaced in place sends the queue once and moves the queue version`() =
    expectOneQueue("x" to false, "b" to false) { player.replaceTrack(0, a.copy(title = "x")) }

  @Test
  fun `a favorite change that moves no queued track sends nothing`() {
    val version = player.queueVersion

    player.applyFavoriteChange(FavoriteChange.between(emptySet(), setOf("elsewhere"))!!)
    idle()

    assertEquals(emptyList<Any>(), queueEvents())
    assertEquals(version, player.queueVersion)
  }

  @Test
  fun `a now-playing restamp leaves the tracks, and sends nothing`() {
    val version = player.queueVersion

    player.nowPlaying.updateNowPlaying(
      NowPlayingUpdate(title = "Song", artist = null, album = null)
    )
    idle()

    assertEquals("Song", player.sessionPlayer.getMediaItemAt(0).mediaMetadata.title)
    assertEquals(emptyList<Any>(), queueEvents())
    assertEquals(version, player.queueVersion)
  }

  @Test
  fun `the active track stays put`() {
    player.applyFavoriteChange(FavoriteChange.between(emptySet(), setOf(a.src!!))!!)
    idle()

    assertEquals(0, calls("onPlaybackActiveTrackChanged").size)
    assertEquals(0, player.sessionPlayer.currentMediaItemIndex)
  }
}
