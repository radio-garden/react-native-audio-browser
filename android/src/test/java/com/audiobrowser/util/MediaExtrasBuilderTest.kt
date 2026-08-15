package com.audiobrowser.util

import androidx.media.utils.MediaConstants
import com.audiobrowser.TestFixtures
import com.audiobrowser.TestFixtures.sectionStyle
import com.audiobrowser.TestFixtures.trackStyle
import com.audiobrowser.browser.styleResolvedSections
import com.margelo.nitro.audiobrowser.StyleDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The extras are where a section survives the flat MediaBrowser protocol (ADR 0010): the owning
 * section stamps the Android Auto group-title header hint and the per-item display hint. Per-item
 * hints derive from the section resolution; a track's own `display` is positional — the page
 * promise a browsable track emits as the parent-level hint, never its own tile shape (ADR 0011).
 */
@RunWith(RobolectricTestRunner::class)
class MediaExtrasBuilderTest {
  private val track = TestFixtures.track()

  private fun sectionStyle(display: StyleDisplay?) =
    sectionStyle(display = display, artworkRendering = null, gridWrap = null)

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
  fun `grid section display stamps the grid item hint`() {
    val section = TestFixtures.section(arrayOf(track), style = sectionStyle(StyleDisplay.GRID))
    val extras = MediaExtrasBuilder.build(track, section)
    assertEquals(
      MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
      extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM),
    )
  }

  @Test
  fun `list section display stamps the list item hint`() {
    val section = TestFixtures.section(arrayOf(track), style = sectionStyle(StyleDisplay.LIST))
    val extras = MediaExtrasBuilder.build(track, section)
    assertEquals(
      MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
      extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM),
    )
  }

  @Test
  fun `a track's own display never sets its item hint - display is positional`() {
    val gridPromiseTrack =
      track.copy(style = trackStyle(display = StyleDisplay.GRID, artworkRendering = null))
    val section = TestFixtures.section(arrayOf(gridPromiseTrack), style = null)
    val extras = MediaExtrasBuilder.build(gridPromiseTrack, section)
    assertFalse(extras.containsKey(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM))
  }

  @Test
  fun `a browsable track's declared display emits the parent-level promise`() {
    val handle =
      TestFixtures.browseTrack()
        .copy(style = trackStyle(display = StyleDisplay.GRID, artworkRendering = null))
    val extras = MediaExtrasBuilder.build(handle)
    val expected = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
    assertEquals(
      expected,
      extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE),
    )
    assertEquals(
      expected,
      extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE),
    )
  }

  @Test
  fun `an undeclared promise emits no parent-level hint - never derived`() {
    val handle = TestFixtures.browseTrack()
    val extras = MediaExtrasBuilder.build(handle)
    assertFalse(extras.containsKey(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
    assertFalse(extras.containsKey(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
  }

  @Test
  fun `a playable track's display is inert - playables open no page`() {
    val playable =
      track.copy(style = trackStyle(display = StyleDisplay.GRID, artworkRendering = null))
    val extras = MediaExtrasBuilder.build(playable)
    assertFalse(extras.containsKey(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
    assertFalse(extras.containsKey(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
  }

  @Test
  fun `no style anywhere stamps no style hint`() {
    val extras = MediaExtrasBuilder.build(track, TestFixtures.section(arrayOf(track)))
    assertFalse(extras.containsKey(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM))
  }

  @Test
  fun `no section (queue and now-playing surfaces) stamps neither section hint`() {
    val extras = MediaExtrasBuilder.build(track)
    assertFalse(extras.containsKey(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
    assertFalse(extras.containsKey(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM))
  }

  @Test
  fun `the view-all navigation track carries the section's declared display as its promise`() {
    // A "view all" page has no consumer-authored handle — the synthetic
    // navigation track projects the section's declared block, the only
    // declared promise that page can ever have.
    val section =
      TestFixtures.section(
        arrayOf(track),
        title = "Popular",
        path = "/popular",
        style = sectionStyle(display = StyleDisplay.GRID, artworkRendering = null, gridWrap = false),
      )
    val navigation = TrackFactory.navigationTrack(section)
    assertEquals(StyleDisplay.GRID, navigation.style?.display)

    val extras = MediaExtrasBuilder.build(navigation)
    assertEquals(
      MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
      extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE),
    )
  }

  @Test
  fun `an unstyled section under a grid page stamps the grid hint end to end`() {
    // The wire that makes ResolvedTrack.style real: page block ->
    // styleResolvedSections fold -> per-item hint. Without the fold, every
    // narrower unit test stays green while the page tier goes dark.
    val page =
      TestFixtures.resolvedTrack(
        style = sectionStyle(display = StyleDisplay.GRID, artworkRendering = null, gridWrap = null),
        sections = arrayOf(TestFixtures.section(arrayOf(track))),
      )

    val folded = page.styleResolvedSections()!!.single()
    val extras = MediaExtrasBuilder.build(track, folded)
    assertEquals(
      MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
      extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM),
    )
  }
}
