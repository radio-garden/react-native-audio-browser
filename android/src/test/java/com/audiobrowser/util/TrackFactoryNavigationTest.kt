package com.audiobrowser.util

import androidx.media.utils.MediaConstants
import androidx.media3.common.MediaItem
import com.audiobrowser.R
import com.audiobrowser.TestFixtures
import com.audiobrowser.TestFixtures.sectionStyle
import com.audiobrowser.browser.BrowseArtworkRegistry
import com.margelo.nitro.audiobrowser.StyleDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackFactoryNavigationTest {
  private val track = TestFixtures.track()
  private val registry = BrowseArtworkRegistry()
  private val routes = ArtworkRoutes(registry, "com.test")
  private val icon = "android.resource://com.test/${R.drawable.audio_browser_view_more}"

  private fun served(item: MediaItem): String {
    val uri = item.mediaMetadata.artworkUri!!
    assertEquals(ArtworkUris.authorityFor("com.test"), uri.authority)
    return registry.lookup(ArtworkUris.parseToken(uri)!!)!!.finalUrl
  }

  private fun section(display: StyleDisplay?) =
    TestFixtures.section(
      arrayOf(track),
      title = "Popular",
      path = "/popular",
      style = display?.let { sectionStyle(display = it, artworkRendering = null, gridWrap = null) },
    )

  @Test
  fun `a grid section's view more tile carries the bundled icon as a plain grid tile`() {
    // A grid tile without artwork is drawn by Android Auto as a broken image.
    val item =
      TrackFactory.toNavigationMediaItem(section(StyleDisplay.GRID), "View more", null, routes)

    assertEquals(icon, served(item))
    val extras = item.mediaMetadata.extras!!
    assertEquals(
      MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
      extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM),
    )
    assertEquals(
      "Popular",
      extras.getString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE),
    )
  }

  @Test
  fun `the view more row promises the section's display for the page it opens`() {
    val item =
      TrackFactory.toNavigationMediaItem(section(StyleDisplay.GRID), "View more", null, routes)

    val extras = item.mediaMetadata.extras!!
    assertEquals(
      MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
      extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE),
    )
    assertEquals(
      MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
      extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE),
    )
  }

  @Test
  fun `a list section's view more row stays a text row`() {
    val item =
      TrackFactory.toNavigationMediaItem(section(StyleDisplay.LIST), "View more", null, routes)

    assertNull(item.mediaMetadata.artworkUri)
    assertEquals(
      MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
      item.mediaMetadata.extras!!.getInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM
      ),
    )
  }

  @Test
  fun `an unstyled section's view more row stays a text row`() {
    val item = TrackFactory.toNavigationMediaItem(section(null), "View more", null, routes)

    assertNull(item.mediaMetadata.artworkUri)
    assertFalse(
      item.mediaMetadata.extras!!.containsKey(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM
      )
    )
  }

  @Test
  fun `the row is titled by its section, which heads the page it opens, and subtitled by the configured title`() {
    val item = TrackFactory.toNavigationMediaItem(section(StyleDisplay.GRID), "More", null, routes)

    assertEquals("/popular", item.mediaId)
    assertEquals("Popular", item.mediaMetadata.title)
    assertEquals("More", item.mediaMetadata.artist)
    assertEquals(true, item.mediaMetadata.isBrowsable)
  }

  @Test
  fun `an untitled or blank-titled section's row takes the configured title`() {
    for (title in listOf(null, " ")) {
      val untitled =
        TestFixtures.section(arrayOf(track), title = title, path = "/popular", style = null)

      val item = TrackFactory.toNavigationMediaItem(untitled, "More", null, routes)

      assertEquals("More", item.mediaMetadata.title)
      assertNull(item.mediaMetadata.artist)
    }
  }

  @Test
  fun `a view more artwork the provider cannot serve falls back to the icon`() {
    val item =
      TrackFactory.toNavigationMediaItem(
        section(StyleDisplay.GRID),
        "View more",
        "file:///sdcard/view-more.png",
        routes,
      )

    assertEquals(icon, served(item))
  }

  @Test
  fun `a percent-encoded data URL view more artwork falls back to the icon`() {
    val item =
      TrackFactory.toNavigationMediaItem(
        section(StyleDisplay.GRID),
        "View more",
        "data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%2F%3E",
        routes,
      )

    assertEquals(icon, served(item))
  }

  @Test
  fun `the tile's icon outlives a clear of the registry, as a car may redraw a cached page`() {
    val item =
      TrackFactory.toNavigationMediaItem(section(StyleDisplay.GRID), "View more", null, routes)

    registry.clear()

    assertEquals(icon, served(item))
  }

  @Test
  fun `a view more artwork the provider serves is a grid tile that outlives a clear of the registry`() {
    val item =
      TrackFactory.toNavigationMediaItem(
        section(StyleDisplay.GRID),
        "View more",
        "https://cdn.example.com/more.png",
        routes,
      )

    registry.clear()

    assertEquals("https://cdn.example.com/more.png", served(item))
    assertEquals(
      MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
      item.mediaMetadata.extras!!.getInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM
      ),
    )
  }
}
