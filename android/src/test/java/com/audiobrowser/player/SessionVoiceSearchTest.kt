package com.audiobrowser.player

import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import androidx.media3.session.legacy.PlaybackStateCompat
import androidx.media3.session.legacyState
import androidx.media3.session.recordLegacyErrors
import com.audiobrowser.AudioBrowser
import com.audiobrowser.TestFixtures
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.buildService
import com.audiobrowser.goOnline
import com.audiobrowser.session
import com.audiobrowser.util.JsPromise
import com.google.common.util.concurrent.ListenableFuture
import com.margelo.nitro.audiobrowser.FormattedNavigationError
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__optional_FormattedNavigationError____FormatNavigationErrorParams
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__shared_ptr_Promise_std__vector_Track______SearchParams
import com.margelo.nitro.audiobrowser.Gate
import com.margelo.nitro.audiobrowser.GateEvent
import com.margelo.nitro.audiobrowser.GateReason
import com.margelo.nitro.audiobrowser.SearchMode
import com.margelo.nitro.audiobrowser.SearchParams
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.core.Promise
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Media3 turns a legacy controller's `playFromSearch` into a set of one media item that has the
 * query and the request's extras, and no media id. Robolectric cannot route a MediaControllerCompat
 * to the session, so these call that callback directly.
 */
@RunWith(RobolectricTestRunner::class)
class SessionVoiceSearchTest {

  private val controller = buildService()
  private val service = controller.get()
  private val player = service.player
  private val legacyController =
    MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "legacy",
      0,
      0,
      MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
      MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
      false,
      Bundle.EMPTY,
      false,
    )

  private val searches = mutableListOf<SearchParams>()
  private val gateEvents = mutableListOf<GateEvent>()

  @After
  fun destroy() {
    controller.destroy()
  }

  private fun browserWhoseSearchAnswers(
    vararg results: Track,
    gated: Boolean = false,
    failure: Throwable? = null,
  ) {
    val answer =
      JsPromise<Array<Track>>().apply {
        if (failure != null) reject(failure) else resolve(arrayOf(*results))
      }
    val bridge = JsPromise<Promise<Array<Track>>>().apply { resolve(answer.promise) }
    val browserManager =
      BrowserManager().apply {
        config =
          BrowserConfig(
            routes =
              arrayOf(
                TestFixtures.staticRoute(
                    BrowserManager.SEARCH_ROUTE_PATH,
                    TestFixtures.resolvedTrack(path = BrowserManager.SEARCH_ROUTE_PATH),
                  )
                  .copy(
                    browseStatic = null,
                    searchCallback =
                      Func_std__shared_ptr_Promise_std__shared_ptr_Promise_std__vector_Track______SearchParams {
                        searches += it
                        bridge.promise
                      },
                  )
              )
          )
      }
    val configuration =
      TestFixtures.browserConfiguration()
        .copy(
          formatNavigationError =
            Func_std__shared_ptr_Promise_std__optional_FormattedNavigationError____FormatNavigationErrorParams {
              JsPromise<FormattedNavigationError?>()
                .apply { resolve(FormattedNavigationError("${it.error.code} ${it.kind}", it.path)) }
                .promise
            }
        )
    val onGate: (GateEvent) -> Unit = { gateEvents += it }
    player.browser =
      mock(
        AudioBrowser::class.java,
        Answer {
          when {
            it.method.name == "gateDecision" ->
              AudioBrowser.GateOutcome(gated, Gate("Gated", null).takeIf { gated })
            it.method.name == "getOnGate" -> onGate
            it.method.name == "getConfiguration" -> configuration
            it.method.name.startsWith("getBrowserManager") -> browserManager
            it.method.name.startsWith("resolveFormattedError") -> it.callRealMethod()
            else -> RETURNS_DEFAULTS.answer(it)
          }
        },
      )
    player.notifyBrowserConfigurationReady()
  }

  /** What Media3 sets for the legacy `playFromSearch(query, extras)`. */
  private fun requestFromSearch(
    query: String,
    extras: Bundle? = null,
    session: MediaSession = service.session,
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    val item =
      MediaItem.Builder()
        .setMediaId(MediaItem.DEFAULT_MEDIA_ID)
        .setRequestMetadata(
          MediaItem.RequestMetadata.Builder().setSearchQuery(query).setExtras(extras).build()
        )
        .build()
    return player
      .getMediaSessionCallback()
      .onSetMediaItems(session, legacyController, mutableListOf(item), C.INDEX_UNSET, C.TIME_UNSET)
  }

  private fun playFromSearch(query: String, extras: Bundle? = null) =
    requestFromSearch(query, extras).get(5, TimeUnit.SECONDS)

  @Test
  fun `a search that names no item queues the playable results from the first`() {
    browserWhoseSearchAnswers(
      TestFixtures.track(title = "unavailable", src = "https://s/x.mp3", disabled = true),
      TestFixtures.track(title = "a", src = "https://s/a.mp3"),
      TestFixtures.track(title = "b", src = "https://s/b.mp3"),
    )

    val result = playFromSearch("jazz")

    assertEquals(listOf("a", "b"), result.mediaItems.map { it.mediaMetadata.title.toString() })
    assertEquals(0, result.startIndex)
    assertEquals(listOf("jazz"), searches.map { it.query })
  }

  @Test
  fun `a search's extras reach the search source as the filters they name`() {
    browserWhoseSearchAnswers(TestFixtures.track(title = "a"))

    playFromSearch(
      "kind of blue",
      Bundle().apply {
        putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
        putString(MediaStore.EXTRA_MEDIA_ARTIST, "miles davis")
      },
    )

    assertEquals(listOf(SearchMode.SONG), searches.map { it.mode })
    assertEquals(listOf<String?>("miles davis"), searches.map { it.artist })
  }

  @Test
  fun `a gated search is refused before it runs, and reports the gate`() {
    browserWhoseSearchAnswers(TestFixtures.track(title = "a"), gated = true)
    val session = mock(MediaSession::class.java)

    val request = requestFromSearch("jazz", session = session)
    assertThrows(ExecutionException::class.java) { request.get(5, TimeUnit.SECONDS) }
    // A report would come from the failing thread after get() returns: give it the time one takes.
    Thread.sleep(200)
    shadowOf(Looper.getMainLooper()).idle()

    // A gate is app state, not a failure: the controller is told nothing.
    verify(session, never()).sendError(any(), any())

    assertEquals(listOf(GateEvent(GateReason.SEARCH)), gateEvents)
    assertEquals(emptyList<SearchParams>(), searches)
  }

  @Test
  fun `a search without a playable result tells the car the app's empty copy, keeping the state`() {
    goOnline(RuntimeEnvironment.getApplication())
    browserWhoseSearchAnswers(
      TestFixtures.track(title = "unavailable", src = "https://s/x.mp3", disabled = true)
    )
    val errors = service.session.recordLegacyErrors()
    val stateBefore = service.session.legacyState()

    assertThrows(ExecutionException::class.java) { playFromSearch("zqx qqq") }
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(listOf(stateBefore to "EMPTY_CONTENT SEARCH\n/__search?q=zqx+qqq"), errors())
    assertEquals(PlaybackStateCompat.STATE_NONE, service.session.legacyState())
  }

  @Test
  fun `a search without a result while offline tells the car it is offline`() {
    browserWhoseSearchAnswers()
    val errors = service.session.recordLegacyErrors()

    assertThrows(ExecutionException::class.java) { playFromSearch("jazz") }
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(listOf("NETWORK_ERROR SEARCH\n/__search?q=jazz"), errors().map { it.second })
  }

  @Test
  fun `a failed search tells the car it failed`() {
    goOnline(RuntimeEnvironment.getApplication())
    browserWhoseSearchAnswers(failure = RuntimeException("search source down"))
    val errors = service.session.recordLegacyErrors()

    assertThrows(ExecutionException::class.java) { playFromSearch("jazz") }
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(listOf("UNKNOWN_ERROR SEARCH\n/__search?q=jazz"), errors().map { it.second })
  }

  @Test
  fun `a gated search tells the car nothing, the gate speaks for it`() {
    browserWhoseSearchAnswers(TestFixtures.track(title = "a"), gated = true)
    val errors = service.session.recordLegacyErrors()

    assertThrows(ExecutionException::class.java) { playFromSearch("jazz") }
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(emptyList<Pair<Int, String>>(), errors())
  }
}
