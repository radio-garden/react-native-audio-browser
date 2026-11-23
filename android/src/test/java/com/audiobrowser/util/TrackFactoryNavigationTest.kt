package com.audiobrowser.util

import androidx.media.utils.MediaConstants
import com.audiobrowser.TestFixtures
import com.audiobrowser.TestFixtures.sectionStyle
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

  private fun section(display: StyleDisplay?) =
    TestFixtures.section(
      arrayOf(track),
      title = "Popular",
      path = "/popular",
      style = display?.let { sectionStyle(display = it, artworkRendering = null, gridWrap = null) },
    )

  @Test
  fun `a grid section's view more tile carries the bundled icon as a category tile`() {
    // A grid tile without artwork is drawn by Android Auto as a broken image.
    val item =
      TrackFactory.toNavigationMediaItem(section(StyleDisplay.GRID), "View more", "com.test")

    val artwork = item.mediaMetadata.artworkUri.toString()
    assertEquals("android.resource://com.test/drawable/audio_browser_view_more", artwork)
    val extras = item.mediaMetadata.extras!!
    assertEquals(
      MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM,
      extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM),
    )
    assertEquals(
      "Popular",
      extras.getString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE),
    )
  }

  @Test
  fun `the icon does not make the page it opens a page of category tiles`() {
    val item =
      TrackFactory.toNavigationMediaItem(section(StyleDisplay.GRID), "View more", "com.test")

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
      TrackFactory.toNavigationMediaItem(section(StyleDisplay.LIST), "View more", "com.test")

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
    val item = TrackFactory.toNavigationMediaItem(section(null), "View more", "com.test")

    assertNull(item.mediaMetadata.artworkUri)
    assertFalse(
      item.mediaMetadata.extras!!.containsKey(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM
      )
    )
  }

  @Test
  fun `the row navigates to the section's path under the configured title`() {
    val item = TrackFactory.toNavigationMediaItem(section(StyleDisplay.GRID), "More", "com.test")

    assertEquals("/popular", item.mediaId)
    assertEquals("More", item.mediaMetadata.title)
    assertEquals(true, item.mediaMetadata.isBrowsable)
  }
}
