package com.audiobrowser.player

import android.net.Uri
import android.os.Looper
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.session.MediaSession
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.browser.FavoriteChange
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.silentWavBytes
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.Track
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/** What JS hears of a favorite change on a prepared queue, as a device has it. */
// SDK 28: from 33, media3 asks AudioManager.getAudioDevicesForAttributes, which Robolectric
// answers with null.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FavoriteQueueEventTest {
  @get:Rule val folder = TemporaryFolder()

  private val looper = Looper.getMainLooper()
  private val app = RuntimeEnvironment.getApplication()
  private val player = Player(app)
  private val callbacks = mock(Callbacks::class.java)
  private lateinit var session: MediaSession
  private lateinit var a: Track
  private lateinit var b: Track

  @Before
  fun setUp() {
    player.setup(PlayerSetupOptions())
    session = MediaSession.Builder(app, player.exoPlayer).build()
    player.setMediaSession(session)
    player.applyOptions(
      PlayerUpdateOptions().let { it.copy(capabilities = it.capabilities.copy(favorite = true)) }
    )
    player.setCallbacks(callbacks)
    a = TestFixtures.track(title = "a", src = wav("a.wav"), favorited = false)
    b = TestFixtures.track(title = "b", src = wav("b.wav"), favorited = false)
    player.setQueue(arrayOf(a, b))
    idleUntilReady()
    clearInvocations(callbacks)
  }

  @After
  fun release() {
    session.release()
    player.exoPlayer.release()
    TrackFactory.favoriteEnabled = false
  }

  private fun wav(name: String) =
    Uri.fromFile(folder.newFile(name).apply { writeBytes(silentWavBytes(Duration.ofSeconds(30))) })
      .toString()

  private fun idleUntilReady() {
    repeat(200) {
      if (player.exoPlayer.playbackState == MediaPlayer.STATE_READY) return
      ShadowSystemClock.advanceBy(Duration.ofMillis(100))
      Thread.sleep(10)
      shadowOf(looper).idle()
    }
    fail("the engine never became ready, in state ${player.exoPlayer.playbackState}")
  }

  private fun idle() = shadowOf(looper).idle()

  private fun calls(name: String) =
    mockingDetails(callbacks).invocations.filter { it.method.name == name }

  /** The `favorited` of each queue JS was sent since the last check. */
  private fun queueEvents(): List<List<Boolean?>> {
    val queues =
      calls("onPlaybackQueueChanged").map { call ->
        (call.arguments[0] as Array<*>).map { (it as Track).favorited }
      }
    clearInvocations(callbacks)
    return queues
  }

  @Test
  fun `adding a favorite sends the restamped queue once and moves the queue version`() {
    val version = player.queueVersion

    player.applyFavoriteChange(FavoriteChange.between(emptySet(), setOf(a.src!!))!!)
    idle()

    assertEquals(listOf(listOf(true, false)), queueEvents())
    assertNotEquals(version, player.queueVersion)
  }

  @Test
  fun `removing a favorite sends the restamped queue once and moves the queue version`() {
    player.applyFavoriteChange(FavoriteChange.between(emptySet(), setOf(a.src!!))!!)
    idle()
    queueEvents()
    val version = player.queueVersion

    player.applyFavoriteChange(FavoriteChange.between(setOf(a.src!!), emptySet())!!)
    idle()

    assertEquals(listOf(listOf(false, false)), queueEvents())
    assertNotEquals(version, player.queueVersion)
  }

  @Test
  fun `a toggle sends the restamped queue once and moves the queue version`() {
    val version = player.queueVersion

    player.setActiveTrackFavorited(true)
    idle()

    assertEquals(listOf(listOf(true, false)), queueEvents())
    assertNotEquals(version, player.queueVersion)
  }

  @Test
  fun `the queue event does not wait on the listener hearing a playlist change`() {
    // A device reports the in-place replace of a prepared queue as a source update, which the
    // listener does not forward; here the listener hears nothing at all.
    player.forwardingPlayer.removeListener(player.playerListener)

    player.applyFavoriteChange(FavoriteChange.between(emptySet(), setOf(a.src!!))!!)
    idle()

    assertEquals(listOf(listOf(true, false)), queueEvents())
  }

  @Test
  fun `a change that moves no queued track sends nothing`() {
    val version = player.queueVersion

    player.applyFavoriteChange(FavoriteChange.between(emptySet(), setOf("elsewhere"))!!)
    idle()

    assertEquals(emptyList<List<Boolean?>>(), queueEvents())
    assertEquals(version, player.queueVersion)
  }

  @Test
  fun `the active track stays put`() {
    player.applyFavoriteChange(FavoriteChange.between(emptySet(), setOf(a.src!!))!!)
    idle()

    assertEquals(0, calls("onPlaybackActiveTrackChanged").size)
    assertEquals(0, player.exoPlayer.currentMediaItemIndex)
  }
}
