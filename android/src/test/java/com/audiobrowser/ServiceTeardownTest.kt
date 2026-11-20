package com.audiobrowser

import android.os.Bundle
import android.os.Looper
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.margelo.nitro.audiobrowser.RepeatMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ServiceTeardownTest {

  private val controller = buildService()
  private val service = controller.get()
  private val player = service.player
  private var destroyed = false

  @After
  fun destroy() {
    if (!destroyed) controller.destroy()
  }

  @Test
  fun `a resumption the teardown cancels reports no failure`() {
    player.load(TestFixtures.track())
    player.playbackStateStore.save()
    player.playbackStateStore.repeatMode = RepeatMode.TRACK
    player.clear()
    var failures = 0
    player.onPlaybackResumptionFailed = { failures += 1 }
    val resumption =
      player
        .getMediaSessionCallback()
        .onPlaybackResumption(service.session, headUnit(), /* isForPlayback= */ true)
    // Restored, then waiting for AudioBrowser, which never registers here.
    idleUntil("the restore") { player.repeatMode == RepeatMode.TRACK }

    controller.destroy()
    destroyed = true
    // Both callbacks post to the main looper, the library's first, so it has run before this one.
    var settled = false
    Futures.addCallback(
      resumption,
      object : FutureCallback<MediaSession.MediaItemsWithStartPosition> {
        override fun onSuccess(result: MediaSession.MediaItemsWithStartPosition) {
          settled = true
        }

        override fun onFailure(t: Throwable) {
          settled = true
        }
      },
      ContextCompat.getMainExecutor(service),
    )
    idleUntil("the resumption's end") { settled }

    assertEquals(0, failures)
  }

  private fun headUnit() =
    MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "com.example.headunit",
      0,
      Process.myUid(),
      0,
      0,
      false,
      Bundle.EMPTY,
      false,
    )

  /** Idles the main looper while the resumption's IO work runs, until [condition] holds. */
  private fun idleUntil(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5_000
    while (!condition()) {
      if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
      Thread.sleep(10)
      shadowOf(Looper.getMainLooper()).idle()
    }
  }
}
