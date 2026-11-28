package com.audiobrowser.util

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtworkUrisTest {
  @Test
  fun `tokenFor is deterministic and opaque`() {
    val url = "https://cdn.example.com/a.png?sig=abc&x=1"
    assertEquals(ArtworkUris.tokenFor(url), ArtworkUris.tokenFor(url))
    assert(!ArtworkUris.tokenFor(url).contains("cdn.example.com"))
  }

  @Test
  fun `contentUri round-trips through parseToken`() {
    val authority = ArtworkUris.authorityFor("com.myapp")
    val token = ArtworkUris.tokenFor("https://cdn.example.com/a.svg")
    val uri = ArtworkUris.contentUri(authority, token)
    assertEquals("content://com.myapp.audiobrowser.artwork/art/$token", uri)
    assertEquals(token, ArtworkUris.parseToken(Uri.parse(uri)))
  }

  @Test
  fun `parseToken rejects malformed uris`() {
    assertNull(ArtworkUris.parseToken(Uri.parse("content://com.myapp.audiobrowser.artwork/nope")))
    assertNull(ArtworkUris.parseToken(Uri.parse("https://example.com/art/x")))
  }

  @Test
  fun `isServable takes http(s) and base64 data URLs`() {
    assertTrue(servable("https://cdn.example.com/a.png"))
    assertTrue(servable("data:image/svg+xml;base64,PHN2Zy8+"))
    assertFalse(servable("data:image/svg+xml,%3Csvg%2F%3E"))
    assertFalse(servable("file:///sdcard/a.png"))
  }

  @Test
  fun `isServable takes the app's own resources by id, the one form Coil loads`() {
    assertTrue(servable("android.resource://com.myapp/2131230800"))
    assertFalse(servable("android.resource://com.other/2131230800"))
    assertFalse(servable("android.resource://com.myapp/drawable/icon"))
    assertFalse(servable("android.resource://com.myapp/"))
  }

  private fun servable(url: String) = ArtworkUris.isServable(Uri.parse(url), "com.myapp")
}
