package com.audiobrowser.util

import android.net.Uri
import com.audiobrowser.browser.BrowseArtworkRegistry
import org.junit.Assert.assertEquals
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
    val routes = ArtworkRoutes(registry, "app.artwork")

    val routed = routes.route(dataUrl, null, isSvg = false, pinned = false)

    assertEquals(ArtworkUris.contentUri("app.artwork", ArtworkUris.tokenFor(dataUrl)), routed)
    assertEquals(dataUrl, routes.publicUrl(Uri.parse(routed)))
  }
}
