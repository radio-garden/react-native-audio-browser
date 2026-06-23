package com.audiobrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseArtworkRegistryTest {
  @Test
  fun `register then lookup returns the entry`() {
    val reg = BrowseArtworkRegistry()
    val art = ResolvedArtwork("https://x/a.png", mapOf("Authorization" to "Bearer t"), isSvg = false)
    reg.register("tok", art)
    assertEquals(art, reg.lookup("tok"))
  }

  @Test
  fun `lookup of unknown token is null`() {
    assertNull(BrowseArtworkRegistry().lookup("missing"))
  }

  @Test
  fun `clear drops entries`() {
    val reg = BrowseArtworkRegistry()
    reg.register("tok", ResolvedArtwork("https://x/a.png", null, false))
    reg.clear()
    assertNull(reg.lookup("tok"))
  }

  @Test
  fun `evicts oldest beyond capacity`() {
    val reg = BrowseArtworkRegistry(maxEntries = 2)
    reg.register("a", ResolvedArtwork("https://x/a", null, false))
    reg.register("b", ResolvedArtwork("https://x/b", null, false))
    reg.register("c", ResolvedArtwork("https://x/c", null, false))
    assertNull(reg.lookup("a")) // evicted
    assertEquals("https://x/c", reg.lookup("c")?.finalUrl)
  }
}
