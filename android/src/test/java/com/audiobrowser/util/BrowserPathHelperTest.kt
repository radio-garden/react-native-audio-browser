package com.audiobrowser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowserPathHelperTest {

  @Test
  fun `build round-trips a src carrying its own query params`() {
    // A src carrying its own query string (signed CDN URL) must survive the
    // build → extract/strip round-trip: an unescaped `&` would split the src
    // into stray query params, truncating the trackId and polluting the
    // parent path.
    val src = "https://cdn.example.com/stream.mp3?token=abc&exp=1699999999"
    val url = BrowserPathHelper.build("/library", src)
    assertEquals(src, BrowserPathHelper.extractTrackId(url))
    assertEquals("/library", BrowserPathHelper.stripTrackId(url))
  }

  @Test
  fun `build round-trips a src with equals and plus`() {
    val src = "https://cdn.example.com/a+b.mp3?sig=x=y"
    val url = BrowserPathHelper.build("/library", src)
    assertEquals(src, BrowserPathHelper.extractTrackId(url))
    assertEquals("/library", BrowserPathHelper.stripTrackId(url))
  }

  @Test
  fun `build stamps the index and round-trips it`() {
    val src = "https://cdn.example.com/stream.mp3?token=abc&exp=1"
    val url = BrowserPathHelper.build("/library", src, 3)
    assertEquals(src, BrowserPathHelper.extractTrackId(url))
    assertEquals(3, BrowserPathHelper.extractIndex(url))
    assertEquals("/library", BrowserPathHelper.stripTrackId(url))
  }

  @Test
  fun `extractIndex returns null without a stamped index`() {
    val url = BrowserPathHelper.build("/library", "song.mp3")
    assertNull(BrowserPathHelper.extractIndex(url))
  }

  @Test
  fun `extractIndex returns null for non-contextual or malformed-index URLs`() {
    assertNull(BrowserPathHelper.extractIndex("/library"))
    assertNull(BrowserPathHelper.extractIndex("/library?__trackId=song.mp3&__index=x"))
    assertNull(BrowserPathHelper.extractIndex("/library?__trackId=song.mp3&__index=-1"))
  }
}
