package com.audiobrowser.browser

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class SimpleBrowserTest {

    @Test
    fun `basic test without promises`() {
        val browserManager = BrowserManager()
        assertEquals("/", browserManager.getPath())
    }

    @Test
    fun `simple router test`() {
        val router = SimpleRouter()
        val routes = mapOf("/test" to "value")
        val match = router.findBestMatch("/test", routes)
        assertNotNull(match)
        assertEquals("/test", match!!.first)
    }

    @Test
    fun `navigate with empty config returns empty list`() = runBlocking {
        val browserManager = BrowserManager()
        browserManager.config = BrowserConfig()
        val result = browserManager.navigate("/test")

        assertEquals("No content configured for this path", result.title)
        assertEquals("/test", result.url)
    }
}