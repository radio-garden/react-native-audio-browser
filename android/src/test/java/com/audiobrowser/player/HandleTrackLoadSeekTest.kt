package com.audiobrowser.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import com.audiobrowser.AudioBrowser
import com.audiobrowser.TestFixtures
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.buildService
import com.audiobrowser.session
import com.audiobrowser.silentWavUri
import com.audiobrowser.util.JsPromise
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.NativeBrowserConfiguration
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackLoadEvent
import com.margelo.nitro.core.Promise
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/** A controller's set of media items keeps where a handleTrackLoad left the player. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HandleTrackLoadSeekTest {

  @get:Rule val folder = TemporaryFolder()

  private val service = buildService()
  private val player = service.get().player

  /**
   * Playlist changes since the handler's player calls ran, which change it too; null until they
   * have.
   */
  private var setsAfterHandler: Int? = null

  @After
  fun destroy() {
    service.destroy()
  }

  private fun handlingTrackLoadWith(onLoad: (Track) -> Unit) {
    // JS runs the handler's player calls on the main thread before its promise settles.
    val handler: (TrackLoadEvent) -> Promise<Promise<Unit>> = { event ->
      val inner = JsPromise<Unit>()
      val outer = JsPromise<Promise<Unit>>()
      Handler(Looper.getMainLooper()).post {
        onLoad(event.track)
        setsAfterHandler = 0
        inner.resolve(Unit)
        outer.resolve(inner.promise)
      }
      outer.promise
    }
    val configuration =
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
        handleTrackLoad = handler,
        androidControllerOfflineError = null,
        viewMoreTitle = null,
        carPlayLoadingTitle = null,
        resolveAlbumPath = null,
        formatNavigationError = null,
      )
    val browserManager = BrowserManager()
    player.browser =
      mock(
        AudioBrowser::class.java,
        Answer {
          when {
            it.method.name == "getConfiguration" -> configuration
            it.method.name.startsWith("getBrowserManager") -> browserManager
            else -> RETURNS_DEFAULTS.answer(it)
          }
        },
      )
    player.notifyBrowserConfigurationReady()
  }

  /**
   * As a controller starts a track: media3 passes no index or position on a reset. Returns once
   * media3 has set the items the callback returned, after the handler.
   */
  private fun setFromController(track: Track) {
    player.exoPlayer.addListener(
      object : MediaPlayer.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
          if (reason == MediaPlayer.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            setsAfterHandler = setsAfterHandler?.plus(1)
          }
        }
      }
    )
    val future =
      MediaController.Builder(RuntimeEnvironment.getApplication(), service.get().session.token)
        .buildAsync()
    idleUntil("the controller to connect") { future.isDone }
    future.get().setMediaItem(TrackFactory.toMedia3(track))
    idleUntil("media3 to set the items") { (setsAfterHandler ?: 0) >= 1 }
  }

  private fun idleUntil(what: String, condition: () -> Boolean) {
    repeat(200) {
      if (condition()) return
      ShadowSystemClock.advanceBy(Duration.ofMillis(100))
      Thread.sleep(10)
      shadowOf(Looper.getMainLooper()).idle()
    }
    fail("timed out waiting for $what, the engine in state ${player.exoPlayer.playbackState}")
  }

  private fun silentTrack(duration: Duration) =
    TestFixtures.track(id = "a", src = folder.silentWavUri(duration))

  @Test
  fun `a load then a seek in the handler holds the seek`() {
    val track = silentTrack(Duration.ofMinutes(2))
    handlingTrackLoadWith {
      player.load(it)
      player.seekTo(60, TimeUnit.SECONDS)
    }

    setFromController(track)

    assertEquals(60_000L, player.exoPlayer.currentPosition)
  }

  @Test
  fun `a setQueue at a position in the handler holds the position`() {
    val track = silentTrack(Duration.ofMinutes(2))
    handlingTrackLoadWith { player.setQueue(arrayOf(it), 0, 60_000) }

    setFromController(track)

    assertEquals(60_000L, player.exoPlayer.currentPosition)
  }

  @Test
  fun `an ended track a handler leaves is set again at its start`() {
    val track = silentTrack(Duration.ofMillis(500))
    player.exoPlayer.trackSelectionParameters =
      player.exoPlayer.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .build()
    player.setQueue(arrayOf(track))
    player.play()
    idleUntil("the track to end") { player.exoPlayer.playbackState == MediaPlayer.STATE_ENDED }
    handlingTrackLoadWith {}

    setFromController(track)

    assertEquals(0L, player.exoPlayer.currentPosition)
  }
}
