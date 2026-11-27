package com.audiobrowser.player

import android.os.Bundle
import android.os.Looper
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.audiobrowser.AudioBrowser
import com.audiobrowser.TestFixtures.resolvedTrack
import com.audiobrowser.TestFixtures.staticRoute
import com.audiobrowser.TestFixtures.track
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.BrowserPathHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LinkedResumptionTest {
  private val player =
    Player(RuntimeEnvironment.getApplication()).apply { setup(PlayerSetupOptions()) }
  private val session = mock(MediaLibraryService.MediaLibrarySession::class.java)
  private val car =
    MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "car",
      0,
      0,
      MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
      MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
      false,
      Bundle.EMPTY,
      false,
    )

  @After fun release() = player.exoPlayer.release()

  @Test
  fun `a resume plays the stored track when its linked page now disables it`() {
    val browserManager =
      BrowserManager().apply {
        config =
          BrowserConfig(
            routes =
              arrayOf(
                staticRoute(
                  "/recent",
                  resolvedTrack(
                    path = "/recent",
                    children = arrayOf(track(id = "b", src = "b", disabled = true)),
                  ),
                )
              )
          )
      }
    player.browser =
      mock(AudioBrowser::class.java).also { `when`(it.browserManager).thenReturn(browserManager) }
    player.notifyBrowserConfigurationReady()
    val stored =
      track(id = "b", src = "b")
        .copy(path = BrowserPathHelper.buildLinked("/recent", "b", "/home", 0))
    player.setQueue(arrayOf(stored))
    player.playbackStateStore.save()

    val future = player.getMediaSessionCallback().onPlaybackResumption(session, car, true)
    for (attempt in 1..250) {
      shadowOf(Looper.getMainLooper()).idle()
      if (future.isDone) break
      Thread.sleep(20)
    }

    assertEquals(listOf("b"), future.get().mediaItems.map { it.mediaId })
  }
}
