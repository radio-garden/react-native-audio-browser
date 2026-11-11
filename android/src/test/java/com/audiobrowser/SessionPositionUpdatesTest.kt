package com.audiobrowser

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.session.countLegacyStateUpdates
import com.audiobrowser.util.TrackFactory
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * What the service's session tells External Surfaces while playback runs with nothing changing,
 * with and without the app's [Service.PERIODIC_POSITION_UPDATES] meta-data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionPositionUpdatesTest {

  @get:Rule val folder = TemporaryFolder()

  private val application = RuntimeEnvironment.getApplication()
  private lateinit var controller: ServiceController<Service>
  private val service
    get() = controller.get()

  private val player
    get() = service.player

  @After
  fun destroy() {
    controller.destroy()
  }

  @Test
  fun `by default the session re-sends the playback state every 3 s`() {
    startService(metaData = null)

    assertEquals(10, legacyStateUpdatesOverSteadyPlayback(Duration.ofSeconds(30)))
  }

  @Test
  fun `the app's meta-data stops the periodic playback state updates`() {
    startService(metaData = Bundle().apply { putBoolean(Service.PERIODIC_POSITION_UPDATES, false) })

    assertEquals(0, legacyStateUpdatesOverSteadyPlayback(Duration.ofSeconds(30)))

    // The counter hears a real change.
    val updates = service.session.countLegacyStateUpdates()
    player.pause()
    advance(Duration.ofMillis(500))
    assertTrue(updates() > 0)
  }

  private fun startService(metaData: Bundle?) {
    shadowOf(application.packageManager)
      .getInternalMutablePackageInfo(application.packageName)
      .applicationInfo!!
      .metaData = metaData
    controller = buildService()
  }

  /** Plays a silent WAV, then counts the legacy playback states sent over [duration]. */
  private fun legacyStateUpdatesOverSteadyPlayback(duration: Duration): Int {
    player.exoPlayer.trackSelectionParameters =
      player.exoPlayer.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .build()
    goOnline(application)
    val src = folder.silentWavUri(Duration.ofSeconds(45))
    player.exoPlayer.setMediaItems(listOf(TrackFactory.toMedia3(TestFixtures.track(src = src))))
    player.exoPlayer.prepare()
    player.play()
    advanceUntil("playing") { player.exoPlayer.isPlaying }
    // Lets the updates that starting playback sends settle.
    advance(Duration.ofSeconds(1))

    val updates = service.session.countLegacyStateUpdates()
    repeat((duration.toMillis() / 500).toInt()) { advance(Duration.ofMillis(500)) }

    assertTrue(player.exoPlayer.isPlaying)
    return updates()
  }

  private fun advance(duration: Duration) {
    ShadowSystemClock.advanceBy(duration)
    Thread.sleep(10)
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun advanceUntil(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5_000
    while (!condition()) {
      if (System.currentTimeMillis() > deadline)
        fail(
          "timed out waiting for $what: ${player.playbackState}, exo ${player.exoPlayer.playbackState}"
        )
      advance(Duration.ofMillis(100))
    }
  }
}
