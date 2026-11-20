package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.transformableConfig
import com.margelo.nitro.audiobrowser.NativeRouteEntry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The page an endpoint answers: `BrowserSource` and `TabsSource` in `src/types/browser.ts`. */
@RunWith(RobolectricTestRunner::class)
class BrowserManagerPageShapeTest {

  private lateinit var server: MockWebServer
  private lateinit var browserManager: BrowserManager

  @Before
  fun setup() {
    server = MockWebServer()
    server.start()
    browserManager = BrowserManager()
    browserManager.config =
      BrowserConfig(
        request = transformableConfig(baseUrl = server.url("/").toString().trimEnd('/')),
        routes =
          arrayOf(
            NativeRouteEntry(
              path = BrowserManager.TABS_ROUTE_PATH,
              browseCallback = null,
              browseConfig = transformableConfig(),
              browseStatic = null,
              searchCallback = null,
              searchConfig = null,
              media = null,
              artwork = null,
            )
          ),
      )
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun answer(body: String) {
    server.enqueue(MockResponse().setBody(body))
  }

  @Test
  fun `a browse page without a path takes the requested path`() {
    answer("""{"title":"Jazz","children":[{"title":"C","src":"https://s/c.mp3"}]}""")

    val page = runBlocking { browserManager.resolve("/genres/jazz") }

    assertEquals("/genres/jazz", page.path)
    assertEquals("Jazz", page.title)
    assertEquals(listOf("C"), page.flattenedChildren?.map { it.title })
  }

  @Test
  fun `a page's own path wins`() {
    answer("""{"path":"/jazz","title":"Jazz"}""")

    val page = runBlocking { browserManager.resolve("/genres/jazz") }

    assertEquals("/jazz", page.path)
  }

  @Test
  fun `a tabs page needs neither title nor path`() {
    answer("""{"children":[{"title":"Home","path":"/home"}]}""")

    val tabs = runBlocking { browserManager.queryTabs() }

    assertEquals(listOf("/home"), tabs.map { it.path })
  }

  @Test
  fun `a browse page without a title does not resolve`() {
    answer("""{"path":"/genres/jazz","children":[]}""")

    assertThrows(HttpStatusException::class.java) {
      runBlocking { browserManager.resolve("/genres/jazz") }
    }
  }
}
