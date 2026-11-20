package com.audiobrowser.util

import com.margelo.nitro.audiobrowser.NavigationErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowserPathHelperTest {

  @Test
  fun `build round-trips a src carrying its own query params`() {
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

  @Test
  fun `a search path is a search, anything else a browse`() {
    assertEquals(
      NavigationErrorKind.SEARCH,
      BrowserPathHelper.navigationErrorKind(BrowserPathHelper.createSearchPath("jazz")),
    )
    assertEquals(
      NavigationErrorKind.BROWSE,
      BrowserPathHelper.navigationErrorKind("/search?q=jazz"),
    )
    assertEquals(NavigationErrorKind.BROWSE, BrowserPathHelper.navigationErrorKind("/__searchable"))
  }

  @Test
  fun `buildUrl returns a url with a scheme or a leading double slash without the base`() {
    val base = "https://api.example.com"
    val urls =
      listOf(
        "HTTPS://other.example.com/a.mp3",
        "content://media/external/audio/media/42",
        "file:///sdcard/Music/a.mp3",
        "android.resource://com.example.app/raw/chime",
        "data:image/png;base64,iVBORw0KGgo=",
        "//cdn.example.com/a.mp3",
      )
    urls.forEach { assertEquals(it, BrowserPathHelper.buildUrl(base, it)) }
  }

  @Test
  fun `buildUrl keeps a path with a colon after its first slash relative`() {
    val base = "https://api.example.com"
    assertEquals("$base/shows/a:b", BrowserPathHelper.buildUrl(base, "/shows/a:b"))
    assertEquals("$base/shows/a:b", BrowserPathHelper.buildUrl(base, "shows/a:b"))
  }
}
