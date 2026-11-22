package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.resolvedTrack
import com.audiobrowser.TestFixtures.staticRoute
import com.audiobrowser.TestFixtures.transformableConfig
import com.audiobrowser.util.JsPromise
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_RequestConfig___RequestConfig_std__optional_std__unordered_map_std__string__std__string__
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__variant_ResolvedTrack__BrowseError__std__shared_ptr_Promise_std__variant_ResolvedTrack__BrowseError_______BrowserSourceCallbackParam
import com.margelo.nitro.audiobrowser.MediaReference
import com.margelo.nitro.audiobrowser.NativeRouteEntry
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.SearchMode
import com.margelo.nitro.audiobrowser.SearchParams
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import com.margelo.nitro.audiobrowser.Variant_ResolvedTrack_BrowseError_Promise_BrowseResult_
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The route params a request's transforms get, which iOS and web match. */
@RunWith(RobolectricTestRunner::class)
class BrowserManagerRouteParamsTest {

  private lateinit var server: MockWebServer
  private lateinit var browserManager: BrowserManager
  private val received = mutableListOf<Map<String, String>?>()

  @Before
  fun setup() {
    server = MockWebServer()
    server.start()
    browserManager = BrowserManager()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  /** A shared request layer whose transform records its params and sets the server's base URL. */
  private fun recordingRequestLayer() =
    TransformableRequestConfig(
      transform = null,
      transformSync =
        Func_std__shared_ptr_Promise_RequestConfig___RequestConfig_std__optional_std__unordered_map_std__string__std__string__ {
          request,
          params ->
          received.add(params)
          val withBaseUrl = request.copy(baseUrl = server.url("/").toString().trimEnd('/'))
          JsPromise<RequestConfig>().apply { resolve(withBaseUrl) }.promise
        },
      method = null,
      path = null,
      baseUrl = null,
      headers = null,
      query = null,
      body = null,
      contentType = null,
      userAgent = null,
    )

  private fun route(
    path: String,
    browseConfig: TransformableRequestConfig? = null,
    searchConfig: TransformableRequestConfig? = null,
  ) =
    NativeRouteEntry(
      path = path,
      browseCallback = null,
      browseConfig = browseConfig,
      browseStatic = null,
      searchCallback = null,
      searchConfig = searchConfig,
      media = null,
      artwork = null,
    )

  @Test
  fun `a search hands its request transforms the search's query params`() {
    browserManager.config =
      BrowserConfig(
        request = recordingRequestLayer(),
        routes =
          arrayOf(
            route(
              BrowserManager.SEARCH_ROUTE_PATH,
              searchConfig = transformableConfig(path = "/search"),
            )
          ),
      )
    server.enqueue(MockResponse().setBody("[]"))

    runBlocking {
      browserManager.search(
        SearchParams(
          mode = SearchMode.SONG,
          query = "jazz",
          genre = null,
          artist = null,
          album = null,
          title = null,
          playlist = null,
          reference = MediaReference.UNKNOWN,
        )
      )
    }

    assertEquals(listOf<Map<String, String>?>(mapOf("q" to "jazz", "mode" to "song")), received)
  }

  @Test
  fun `the default route hands its request transforms the path`() {
    browserManager.config =
      BrowserConfig(
        request = recordingRequestLayer(),
        routes =
          arrayOf(route(BrowserManager.DEFAULT_ROUTE_PATH, browseConfig = transformableConfig())),
      )
    server.enqueue(MockResponse().setBody("""{"title": "Anything", "children": []}"""))

    runBlocking { browserManager.resolve("/anything") }

    assertEquals(listOf<Map<String, String>?>(mapOf("path" to "/anything")), received)
  }

  @Test
  fun `the default route hands its browse callback the path`() {
    browserManager.config =
      BrowserConfig(
        routes =
          arrayOf(
            staticRoute(BrowserManager.DEFAULT_ROUTE_PATH, resolvedTrack(path = "/anything"))
              .copy(
                browseStatic = null,
                browseCallback =
                  Func_std__shared_ptr_Promise_std__variant_ResolvedTrack__BrowseError__std__shared_ptr_Promise_std__variant_ResolvedTrack__BrowseError_______BrowserSourceCallbackParam {
                    param ->
                    received.add(param.routeParams)
                    JsPromise<Variant_ResolvedTrack_BrowseError_Promise_BrowseResult_>()
                      .apply {
                        resolve(
                          Variant_ResolvedTrack_BrowseError_Promise_BrowseResult_.create(
                            resolvedTrack(path = param.path)
                          )
                        )
                      }
                      .promise
                  },
              )
          )
      )

    runBlocking { browserManager.resolve("/anything") }

    assertEquals(listOf<Map<String, String>?>(mapOf("path" to "/anything")), received)
  }
}
