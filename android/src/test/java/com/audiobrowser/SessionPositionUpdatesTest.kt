package com.audiobrowser

import android.content.ComponentName
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.countLegacyStateUpdates
import com.audiobrowser.util.TrackFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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
    val updates = session().countLegacyStateUpdates()
    player.pause()
    advance(Duration.ofMillis(500))
    assertTrue(updates() > 0)
  }

  private fun startService(metaData: Bundle?) {
    shadowOf(application.packageManager)
      .getInternalMutablePackageInfo(application.packageName)
      .applicationInfo!!
      .metaData = metaData
    // Robolectric connects an unregistered binding with a null ComponentName, which the
    // HeadlessTaskService connection's non-null parameter rejects.
    shadowOf(application)
      .setComponentNameAndServiceForBindService(
        ComponentName(application, HeadlessTaskService::class.java),
        Binder(),
      )
    controller = Robolectric.buildService(Service::class.java).create()
  }

  /** Plays a silent WAV, then counts the legacy playback states sent over [duration]. */
  private fun legacyStateUpdatesOverSteadyPlayback(duration: Duration): Int {
    val session = session()
    player.exoPlayer.trackSelectionParameters =
      player.exoPlayer.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .build()
    goOnline(application)
    val src = Uri.fromFile(silentWav(seconds = 45)).toString()
    player.exoPlayer.setMediaItems(listOf(TrackFactory.toMedia3(TestFixtures.track(src = src))))
    player.exoPlayer.prepare()
    player.play()
    advanceUntil("playing") { player.exoPlayer.isPlaying }
    // Lets the updates that starting playback sends settle.
    advance(Duration.ofSeconds(1))

    val updates = session.countLegacyStateUpdates()
    repeat((duration.toMillis() / 500).toInt()) { advance(Duration.ofMillis(500)) }

    assertTrue(player.exoPlayer.isPlaying)
    return updates()
  }

  @OptIn(UnstableApi::class)
  private fun session(): MediaSession {
    // onGetSession ignores its argument.
    return service.onGetSession(
      MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        application.packageName,
        0,
        Process.myUid(),
        0,
        0,
        false,
        Bundle.EMPTY,
        false,
      )
    )!!
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

  /** 8 kHz mono 8-bit silence. */
  private fun silentWav(seconds: Int): File {
    val samples = 8_000 * seconds
    val header =
      ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt(36 + samples)
        put("WAVEfmt ".toByteArray())
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(8_000)
        putInt(8_000)
        putShort(1)
        putShort(8)
        put("data".toByteArray())
        putInt(samples)
      }
    return folder.newFile("silence.wav").apply {
      writeBytes(header.array() + ByteArray(samples) { 0x80.toByte() })
    }
  }
}
