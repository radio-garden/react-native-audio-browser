package com.audiobrowser

import android.os.Looper
import com.audiobrowser.TestFixtures.resolvedTrack
import com.audiobrowser.TestFixtures.staticRoute
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.util.BrowserPathHelper
import com.margelo.nitro.audiobrowser.NativeBrowserConfiguration
import com.margelo.nitro.audiobrowser.Track
import java.time.Duration
import kotlinx.coroutines.MainScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Selecting a Playable track through `navigate(track)` plays it, whatever its path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NavigateTrackTest {

  @get:Rule val folder = TemporaryFolder()

  private val service = buildService()
  private val player = service.get().player
  private val browserManager = BrowserManager()

  @After
  fun destroy() {
    service.destroy()
  }

  // AudioBrowser's constructor needs JNI: a mock running its real methods, with the fields
  // navigateTrack reads written as the constructor and the service connection write them.
  private val browser =
    mock(AudioBrowser::class.java, CALLS_REAL_METHODS).apply {
      set("mainScope", MainScope())
      set("connectedService", service.get())
      set("browserManager", this@NavigateTrackTest.browserManager)
      set(
        "_configuration",
        NativeBrowserConfiguration(
          path = null,
          request = null,
          requestResolver = null,
          browse = null,
          browseResolver = null,
          media = null,
          artwork = null,
          nowPlayingArtwork = null,
          routes = null,
          singleTrack = null,
          handleTrackLoad = null,
          androidControllerOfflineError = null,
          viewMoreTitle = null,
          carPlayLoadingTitle = null,
          resolveAlbumPath = null,
          formatNavigationError = null,
        ),
      )
    }

  private fun AudioBrowser.set(field: String, value: Any) {
    AudioBrowser::class.java.getDeclaredField(field).apply { isAccessible = true }.set(this, value)
  }

  private fun navigate(track: Track) {
    browser.navigateTrack(track)
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun silentTrack() =
    TestFixtures.track(id = "a", src = folder.silentWavUri(Duration.ofMinutes(2)))

  @Test
  fun `a track with only a src plays`() {
    val track = silentTrack()
    assertFalse(player.playWhenReady)

    navigate(track)

    assertEquals(listOf(track.src), player.tracks.map { it.src })
    assertTrue(player.playWhenReady)
  }

  @Test
  fun `a track whose contextual path does not expand plays alone`() {
    // The page no longer lists the track, so the expansion returns nothing.
    browserManager.config =
      BrowserConfig(
        routes =
          arrayOf(
            staticRoute(
              "/stations",
              resolvedTrack(path = "/stations", children = arrayOf(TestFixtures.track(id = "b"))),
            )
          )
      )
    val track = silentTrack().copy(path = BrowserPathHelper.build("/stations", "a"))

    navigate(track)

    assertEquals(listOf(track.src), player.tracks.map { it.src })
    assertTrue(player.playWhenReady)
  }
}
