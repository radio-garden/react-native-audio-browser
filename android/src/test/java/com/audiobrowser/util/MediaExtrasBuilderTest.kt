package com.audiobrowser.util

import androidx.media.utils.MediaConstants
import com.audiobrowser.TestFixtures
import com.margelo.nitro.audiobrowser.SectionStyle
import com.margelo.nitro.audiobrowser.TrackStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The extras are where a section survives the flat MediaBrowser protocol (ADR 0010): the owning
 * section stamps the Android Auto group-title header hint and the per-item style default.
 */
@RunWith(RobolectricTestRunner::class)
class MediaExtrasBuilderTest {
  private val track = TestFixtures.track()

  @Test
  fun `owning section title stamps the group-title hint`() {
    val section = TestFixtures.section(arrayOf(track), title = "Popular")
    val extras = MediaExtrasBuilder.build(track, section)
    assertEquals(
      "Popular",
      extras.getString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE),
    )
  }

  @Test
  fun `an untitled section stamps no group-title hint`() {
    val section = TestFixtures.section(arrayOf(track))
    val extras = MediaExtrasBuilder.build(track, section)
    assertFalse(extras.containsKey(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
  }

  @Test
  fun `tile section styles coerce to the grid item hint`() {
    for (style in listOf(SectionStyle.GRID, SectionStyle.GRID_ROW)) {
      val section = TestFixtures.section(arrayOf(track), style = style)
      val extras = MediaExtrasBuilder.build(track, section)
      assertEquals(
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
        extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM),
      )
    }
  }

  @Test
  fun `list section style renders as a list row`() {
    val section = TestFixtures.section(arrayOf(track), style = SectionStyle.LIST)
    val extras = MediaExtrasBuilder.build(track, section)
    assertEquals(
      MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
      extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM),
    )
  }

  @Test
  fun `an explicit per-track style wins over the section default`() {
    val listTrack = track.copy(style = TrackStyle.LIST)
    val section = TestFixtures.section(arrayOf(listTrack), style = SectionStyle.GRID)
    val extras = MediaExtrasBuilder.build(listTrack, section)
    assertEquals(
      MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
      extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM),
    )
  }

  @Test
  fun `no style anywhere stamps no style hint`() {
    val extras = MediaExtrasBuilder.build(track, TestFixtures.section(arrayOf(track)))
    assertFalse(extras.containsKey(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM))
  }

  @Test
  fun `no section (queue and now-playing surfaces) stamps neither hint`() {
    val extras = MediaExtrasBuilder.build(track)
    assertFalse(extras.containsKey(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
    assertFalse(extras.containsKey(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM))
  }
}
