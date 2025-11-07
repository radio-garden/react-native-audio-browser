package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.BrowserList
import com.margelo.nitro.audiobrowser.BrowserSource
import com.margelo.nitro.audiobrowser.BrowserSourceCallbackParam
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.core.Promise
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.MainScope
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

class BrowserManagerTest {

    private lateinit var browserManager: BrowserManager

    @Before
    fun setup() {
        browserManager = BrowserManager()
    }

    @Test
    fun `getPath returns default path initially`() {
        assertEquals("/", browserManager.getPath())
    }

    @Test
    fun `navigate with no routes and no browse fallback returns empty list`() = runBlocking {
        val config = BrowserConfig()
        val result = browserManager.navigate("/test", config)

        assertEquals("No content configured for this path", result.title)
        assertEquals("/test", result.url)
        assertTrue(result.children.isEmpty())
    }

    @Test
    fun `navigate updates current path`() = runBlocking {
        val config = BrowserConfig()
        browserManager.navigate("/artists/123", config)

        assertEquals("/artists/123", browserManager.getPath())
    }

    @Test
    fun `navigate with route match uses route source`() = runBlocking {
        // Create a mock static BrowserList
        val staticList = BrowserList(
            children = arrayOf(),
            style = null,
            playable = null,
            url = "/artists/123",
            title = "Artist 123",
            subtitle = null,
            icon = null,
            artwork = null,
            artist = null,
            album = null,
            description = null,
            genre = null,
            duration = null
        )

        val routes = mapOf(
            "/artists/{id}" to BrowserSource.create(staticList)
        )

        val config = BrowserConfig(routes = routes)
        val result = browserManager.navigate("/artists/123", config)

        assertEquals("Artist 123", result.title)
        assertEquals("/artists/123", result.url)
    }

    @Test
    fun `navigate with browse fallback when no route matches`() = runBlocking {
        // Create a mock static BrowserList for browse
        val browseList = BrowserList(
            children = arrayOf(),
            style = null,
            playable = null,
            url = "/unknown",
            title = "Browse Fallback",
            subtitle = null,
            icon = null,
            artwork = null,
            artist = null,
            album = null,
            description = null,
            genre = null,
            duration = null
        )

        val browse = com.margelo.nitro.audiobrowser.Variant__param__BrowserSourceCallbackParam_____Promise_Promise_BrowserList___TransformableRequestConfig_BrowserList.create(browseList)

        val config = BrowserConfig(browse = browse)
        val result = browserManager.navigate("/unknown", config)

        assertEquals("Browse Fallback", result.title)
    }

    @Test
    fun `search with no search source returns empty array`() = runBlocking {
        val config = BrowserConfig()
        val result = browserManager.search("test query", config)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `route matching respects specificity`() = runBlocking {
        val genericList = BrowserList(
            children = arrayOf(),
            style = null,
            playable = null,
            url = "/artists/popular",
            title = "Generic Artist",
            subtitle = null,
            icon = null,
            artwork = null,
            artist = null,
            album = null,
            description = null,
            genre = null,
            duration = null
        )

        val specificList = BrowserList(
            children = arrayOf(),
            style = null,
            playable = null,
            url = "/artists/popular",
            title = "Popular Artists",
            subtitle = null,
            icon = null,
            artwork = null,
            artist = null,
            album = null,
            description = null,
            genre = null,
            duration = null
        )

        val routes = mapOf(
            "/artists/{id}" to BrowserSource.create(genericList),
            "/artists/popular" to BrowserSource.create(specificList)
        )

        val config = BrowserConfig(routes = routes)
        val result = browserManager.navigate("/artists/popular", config)

        // Should match the more specific route
        assertEquals("Popular Artists", result.title)
    }

    @Test
    fun `route parameters are extracted correctly`() = runBlocking {
        // Create a mock static BrowserList instead of using callbacks
        val testList = BrowserList(
            children = arrayOf(),
            style = null,
            playable = null,
            url = "/artists/123/albums/456",
            title = "Test Result",
            subtitle = null,
            icon = null,
            artwork = null,
            artist = null,
            album = null,
            description = null,
            genre = null,
            duration = null
        )

        val routes = mapOf(
            "/artists/{id}/albums/{albumId}" to BrowserSource.create(testList)
        )

        val config = BrowserConfig(routes = routes)
        val result = browserManager.navigate("/artists/123/albums/456", config)

        // Verify the route was matched and static content returned
        assertEquals("Test Result", result.title)
        assertEquals("/artists/123/albums/456", result.url)

        // Also verify the SimpleRouter extracted params correctly
        val router = SimpleRouter()
        val match = router.findBestMatch("/artists/123/albums/456", routes)
        assertNotNull(match)
        assertEquals("123", match!!.second.params["id"])
        assertEquals("456", match.second.params["albumId"])
    }

    @Test
    fun `wildcard routes work correctly`() = runBlocking {
        val wildcardList = BrowserList(
            children = arrayOf(),
            style = null,
            playable = null,
            url = "/files/anything",
            title = "Wildcard Match",
            subtitle = null,
            icon = null,
            artwork = null,
            artist = null,
            album = null,
            description = null,
            genre = null,
            duration = null
        )

        val routes = mapOf(
            "/files/*" to BrowserSource.create(wildcardList)
        )

        val config = BrowserConfig(routes = routes)
        val result = browserManager.navigate("/files/document.pdf", config)

        assertEquals("Wildcard Match", result.title)
    }

    @Test
    fun `tail wildcard routes work correctly`() = runBlocking {
        // Create a mock static BrowserList
        val testList = BrowserList(
            children = arrayOf(),
            style = null,
            playable = null,
            url = "/files/docs/readme.md",
            title = "File Browser",
            subtitle = null,
            icon = null,
            artwork = null,
            artist = null,
            album = null,
            description = null,
            genre = null,
            duration = null
        )

        val routes = mapOf(
            "/files/**" to BrowserSource.create(testList)
        )

        val config = BrowserConfig(routes = routes)
        val result = browserManager.navigate("/files/docs/readme.md", config)

        // Verify the route was matched
        assertEquals("File Browser", result.title)

        // Also verify the SimpleRouter captured tail correctly
        val router = SimpleRouter()
        val match = router.findBestMatch("/files/docs/readme.md", routes)
        assertNotNull(match)
        assertEquals("/files/**", match!!.first)
        assertEquals("docs/readme.md", match.second.params["tail"])
    }

    @Test
    fun `error handling can be tested with invalid config`() = runBlocking {
        // Test error handling by creating an invalid scenario
        // Since we can't easily create throwing callbacks, we can test other error scenarios
        val browserManager = BrowserManager()

        // This should not throw but handle gracefully
        val config = BrowserConfig()
        val result = browserManager.navigate("", config)

        // Should return a valid response even for empty path
        assertNotNull(result)
        assertNotNull(result.title)
    }
}