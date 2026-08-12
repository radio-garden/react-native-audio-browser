package com.audiobrowser.browser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// TODO: Most tests disabled — BrowserConfig API changed (routes is now Array<NativeRouteEntry>).
// When revived, add expandQueueFromContextualUrl glue tests: found id → its
// SectionScope section as the queue; vanished id → null (single-track
// fallback). The section logic and the caller fallback are covered
// (SectionScopeTest, TrackSelectorTests); the wiring between them is not.
// Tests that used BrowserSource/BrowserList need to be rewritten.

@RunWith(RobolectricTestRunner::class)
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
  fun `navigate with no routes throws ContentNotFoundException`() {
    runBlocking {
      browserManager.config = BrowserConfig()
      try {
        browserManager.navigate("/test")
        fail("Expected ContentNotFoundException")
      } catch (e: ContentNotFoundException) {
        assertEquals("/test", e.path)
      }
    }
  }

  @Test
  fun `navigate updates current path`() {
    runBlocking {
      browserManager.config = BrowserConfig()
      try {
        browserManager.navigate("/artists/123")
      } catch (_: ContentNotFoundException) {}

      assertEquals("/artists/123", browserManager.getPath())
    }
  }

  @Test
  fun `navigate with empty path throws ContentNotFoundException`() {
    runBlocking {
      browserManager.config = BrowserConfig()
      try {
        browserManager.navigate("")
        fail("Expected ContentNotFoundException")
      } catch (_: ContentNotFoundException) {}
    }
  }
}
