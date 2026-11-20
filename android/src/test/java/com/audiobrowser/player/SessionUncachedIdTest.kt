package com.audiobrowser.player

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.audiobrowser.AudioBrowser
import com.audiobrowser.TestFixtures
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.buildService
import com.audiobrowser.session
import com.google.common.util.concurrent.ListenableFuture
import com.margelo.nitro.audiobrowser.NativeRouteEntry
import com.margelo.nitro.audiobrowser.Track
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A controller can send an id this process never browsed: a car resuming its last track after the
 * process died. The id is a bare stable id, with no uri, so only the consumer can say what it
 * plays.
 */
@RunWith(RobolectricTestRunner::class)
class SessionUncachedIdTest {

  private val controller = buildService()
  private val service = controller.get()
  private val player = service.player
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
  private val session = mock(MediaSession::class.java)

  private val asked = mutableListOf<String>()

  @After
  fun destroy() {
    controller.destroy()
  }

  private fun station(id: String) =
    TestFixtures.track(title = "Station", id = id, src = "https://s/$id.mp3")

  private lateinit var browserManager: BrowserManager

  private fun browserWith(
    ready: Boolean = true,
    routes: Array<NativeRouteEntry>? = null,
    findTrack: (suspend (String) -> Track?)?,
  ) {
    browserManager =
      BrowserManager().apply {
        config =
          BrowserConfig(
            routes = routes,
            findTrack =
              findTrack?.let { find ->
                val asking: suspend (String) -> Track? = { id ->
                  asked += id
                  find(id)
                }
                asking
              },
          )
      }
    val configuration = TestFixtures.browserConfiguration()
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
    if (ready) player.notifyBrowserConfigurationReady()
  }

  /** What Media3 sets for the legacy `playFromMediaId(id)`: an id, no uri, no metadata. */
  private fun requestPlay(
    mediaId: String
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
    player
      .getMediaSessionCallback()
      .onSetMediaItems(
        session,
        car,
        mutableListOf(MediaItem.Builder().setMediaId(mediaId).build()),
        C.INDEX_UNSET,
        C.TIME_UNSET,
      )

  private fun playFromMediaId(mediaId: String) = requestPlay(mediaId).get(5, TimeUnit.SECONDS)

  private fun getItem(mediaId: String): LibraryResult<MediaItem> =
    player
      .getMediaSessionCallback()
      .onGetItem(service.session, car, mediaId)
      .get(5, TimeUnit.SECONDS)

  /**
   * The error codes sent to the car, waiting up to [waitMs] for the first. The library reports from
   * the thread that failed the future, then posts to Main, so the report can land after `get()`
   * returns.
   */
  private fun errorsSentToTheCar(waitMs: Long = 5_000): List<Int> {
    repeat((waitMs / 20).toInt()) {
      shadowOf(Looper.getMainLooper()).idle()
      val sent =
        mockingDetails(session).invocations.filter {
          it.method.name == "sendError" && it.arguments[0] == car
        }
      if (sent.isNotEmpty()) return sent.map { (it.arguments[1] as SessionError).code }
      Thread.sleep(20)
    }
    return emptyList()
  }

  private fun assertPlayFailsAndTellsTheCar(mediaId: String) {
    val request = requestPlay(mediaId)
    assertThrows(ExecutionException::class.java) { request.get(5, TimeUnit.SECONDS) }
    assertEquals(listOf(SessionError.ERROR_UNKNOWN), errorsSentToTheCar())
  }

  @Test
  fun `an id never browsed plays the track the consumer finds for it`() {
    browserWith { id -> station(id) }

    val item = playFromMediaId("U1eRRwaa").mediaItems.single()

    assertEquals(listOf("U1eRRwaa"), asked)
    assertEquals("Station", item.mediaMetadata.title.toString())
    assertEquals("https://s/U1eRRwaa.mp3", item.localConfiguration?.uri.toString())
  }

  @Test
  fun `a found track plays under the id the controller sent`() {
    browserWith { TestFixtures.track(title = "Station", id = "other", src = "https://s/a.mp3") }

    assertEquals("U1eRRwaa", playFromMediaId("U1eRRwaa").mediaItems.single().mediaId)
  }

  @Test
  fun `a found track is remembered, so a second play does not ask again`() {
    browserWith { id -> station(id) }

    playFromMediaId("U1eRRwaa")
    playFromMediaId("U1eRRwaa")

    assertEquals(listOf("U1eRRwaa"), asked)
  }

  @Test
  fun `an item lookup for an id never browsed answers the track the consumer finds`() {
    browserWith { id -> station(id) }

    val result = getItem("U1eRRwaa")

    assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
    assertEquals("Station", result.value?.mediaMetadata?.title.toString())
    assertEquals(listOf("U1eRRwaa"), asked)
  }

  @Test
  fun `a browse path or a url is never asked of the consumer`() {
    browserWith { id -> station(id) }

    getItem("/library/missing")
    assertThrows(ExecutionException::class.java) { playFromMediaId("/library/missing") }
    playFromMediaId("https://s/a.mp3")
    getItem("https://example.invalid/a.mp3")

    assertEquals(emptyList<String>(), asked)
  }

  @Test
  fun `an id with a scheme-like prefix is asked of the consumer`() {
    browserWith { id -> station(id) }

    playFromMediaId("station:123")

    assertEquals(listOf("station:123"), asked)
  }

  @Test
  fun `an id the consumer does not know fails the play and tells the controller`() {
    browserWith { null }

    assertPlayFailsAndTellsTheCar("gone")
    assertEquals(listOf("gone"), asked)
  }

  @Test
  fun `a found track without a src fails the play and tells the controller`() {
    browserWith { id -> TestFixtures.track(title = "Page", id = id, src = null) }

    assertPlayFailsAndTellsTheCar("U1eRRwaa")
    assertEquals(listOf("U1eRRwaa"), asked)
  }

  @Test
  fun `a find that throws fails the play and tells the controller`() {
    browserWith { throw IllegalStateException("offline") }

    assertPlayFailsAndTellsTheCar("U1eRRwaa")
    assertEquals(listOf("U1eRRwaa"), asked)
  }

  @Test
  fun `without findTrack an id never browsed fails the play and tells the controller`() {
    browserWith(findTrack = null)

    assertPlayFailsAndTellsTheCar("U1eRRwaa")
  }

  @Test
  fun `a play that arrives before the browser is configured plays once it is`() {
    browserWith(ready = false) { id -> station(id) }

    val request = requestPlay("U1eRRwaa")
    assertFalse(request.isDone)
    player.notifyBrowserConfigurationReady()

    assertEquals(
      "Station",
      request.get(5, TimeUnit.SECONDS).mediaItems.single().mediaMetadata.title.toString(),
    )
  }

  @Test
  fun `a play and an item lookup for the same id in flight ask the consumer once`() {
    val answer = CompletableDeferred<Track>()
    browserWith { answer.await() }

    val lookup = player.getMediaSessionCallback().onGetItem(service.session, car, "U1eRRwaa")
    val play = requestPlay("U1eRRwaa")
    waitUntil { asked.isNotEmpty() }
    answer.complete(station("U1eRRwaa"))

    assertEquals(LibraryResult.RESULT_SUCCESS, lookup.get(5, TimeUnit.SECONDS).resultCode)
    assertEquals("U1eRRwaa", play.get(5, TimeUnit.SECONDS).mediaItems.single().mediaId)
    assertEquals(listOf("U1eRRwaa"), asked)
  }

  @Test
  fun `a found track is remembered under the id only, not its path or src`() {
    browserWith {
      TestFixtures.track(title = "Station", id = "x", src = "https://s/a.mp3")
        .copy(path = "/page/a")
    }

    playFromMediaId("U1eRRwaa")

    assertNull(browserManager.getCachedTrack("/page/a"))
    assertNull(browserManager.getCachedTrack("https://s/a.mp3"))
  }

  @Test
  fun `an item lookup for an id the consumer does not know is not browsed as a path`() {
    browserWith(
      routes =
        arrayOf(
          TestFixtures.staticRoute(BrowserManager.DEFAULT_ROUTE_PATH, TestFixtures.resolvedTrack())
        )
    ) {
      null
    }

    assertEquals(SessionError.ERROR_BAD_VALUE, getItem("U1eRRwaa").resultCode)
  }

  @Test
  fun `a newer play cancels an earlier one still finding its track`() {
    val answer = CompletableDeferred<Track>()
    browserWith { answer.await() }

    val earlier = requestPlay("U1eRRwaa")
    waitUntil { asked.isNotEmpty() }
    val newer =
      player
        .getMediaSessionCallback()
        .onSetMediaItems(
          session,
          car,
          mutableListOf(
            MediaItem.Builder()
              .setMediaId("B")
              .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                  .setMediaUri(android.net.Uri.parse("https://s/b.mp3"))
                  .build()
              )
              .build()
          ),
          C.INDEX_UNSET,
          C.TIME_UNSET,
        )

    assertEquals("B", newer.get(5, TimeUnit.SECONDS).mediaItems.single().mediaId)
    answer.complete(station("U1eRRwaa"))
    assertThrows(CancellationException::class.java) { earlier.get(5, TimeUnit.SECONDS) }
    assertEquals(emptyList<Int>(), errorsSentToTheCar(waitMs = 200))
  }

  @Test
  fun `a play from the app while a car's play is finding its track is not replaced by it`() {
    val answer = CompletableDeferred<Track>()
    browserWith { answer.await() }

    val carPlay = requestPlay("U1eRRwaa")
    waitUntil { asked.isNotEmpty() }
    player.load(station("app"))
    shadowOf(Looper.getMainLooper()).idle()
    answer.complete(station("U1eRRwaa"))

    assertThrows(CancellationException::class.java) { carPlay.get(5, TimeUnit.SECONDS) }
    assertEquals(emptyList<Int>(), errorsSentToTheCar(waitMs = 200))
  }

  @Test
  fun `a new queue from the app while a car's play is finding its track drops the car's play`() {
    val answer = CompletableDeferred<Track>()
    browserWith { answer.await() }

    val carPlay = requestPlay("U1eRRwaa")
    waitUntil { asked.isNotEmpty() }
    player.setQueue(arrayOf(station("app")))
    shadowOf(Looper.getMainLooper()).idle()
    answer.complete(station("U1eRRwaa"))

    assertThrows(CancellationException::class.java) { carPlay.get(5, TimeUnit.SECONDS) }
  }

  @Test
  fun `a favorite restamp while a car's play is finding its track does not drop it`() {
    assertCarPlaySurvives { player.replaceTrack(0, station("app").copy(favorited = true)) }
  }

  @Test
  fun `an up-next add while a car's play is finding its track does not drop it`() {
    assertCarPlaySurvives { player.add(station("next")) }
  }

  /** A car play of an unbrowsed id plays, though [meanwhile] edits the queue during its find. */
  private fun assertCarPlaySurvives(meanwhile: () -> Unit) {
    val answer = CompletableDeferred<Track>()
    browserWith { answer.await() }
    player.load(station("app"))
    shadowOf(Looper.getMainLooper()).idle()

    val carPlay = requestPlay("U1eRRwaa")
    waitUntil { asked.isNotEmpty() }
    meanwhile()
    shadowOf(Looper.getMainLooper()).idle()
    answer.complete(station("U1eRRwaa"))

    assertEquals("U1eRRwaa", carPlay.get(5, TimeUnit.SECONDS).mediaItems.single().mediaId)
  }

  private fun waitUntil(condition: () -> Boolean) {
    repeat(250) {
      if (condition()) return
      Thread.sleep(20)
    }
    throw AssertionError("condition not met within 5 s")
  }
}
