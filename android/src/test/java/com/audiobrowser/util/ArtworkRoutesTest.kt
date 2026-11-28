package com.audiobrowser.util

import android.net.Uri
import com.audiobrowser.browser.BrowseArtworkRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtworkRoutesTest {
  private val dataUrl =
    "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAEElEQVR4nGP4z8AARwzEcQCukw/x0F8jngAAAABJRU5ErkJggg=="

  @Test
  fun `route serves a data URL through the provider`() {
    val registry = BrowseArtworkRegistry()
    val routes = ArtworkRoutes(registry, "app")

    val routed = routes.route(dataUrl, null, isSvg = false, pinned = false)

    assertEquals(
      ArtworkUris.contentUri(ArtworkUris.authorityFor("app"), ArtworkUris.tokenFor(dataUrl)),
      routed,
    )
    assertEquals(dataUrl, routes.publicUrl(Uri.parse(routed)))
  }

  @Test
  fun `route refuses a URL the provider would not serve`() {
    val routes = ArtworkRoutes(BrowseArtworkRegistry(), "app")

    assertNull(routes.route("file:///sdcard/a.png", null, isSvg = false, pinned = false))
    assertNull(
      routes.route("android.resource://app/drawable/ic", null, isSvg = false, pinned = true)
    )
  }

  @Test
  fun `route hands back one of the provider's own URIs as it is`() {
    val routes = ArtworkRoutes(BrowseArtworkRegistry(), "app")
    val routed = routes.route(dataUrl, null, isSvg = false, pinned = false)!!

    assertEquals(routed, routes.route(routed, null, isSvg = false, pinned = true))
  }
}
