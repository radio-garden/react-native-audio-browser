package com.audiobrowser.util

import android.os.Bundle
import androidx.media.utils.MediaConstants
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.StyleDisplay
import com.margelo.nitro.audiobrowser.Track

object MediaExtrasBuilder {

  /**
   * The CATEGORY_* variants pad the artwork, which suits the small vector icons an
   * android.resource:// URI points at.
   *
   * @see <a href="https://developer.android.com/training/cars/media#default-content-style">Default
   *   content style</a>
   */
  private fun StyleDisplay.toContentStyleValue(artwork: String?): Int {
    val isAndroidResource = artwork?.startsWith("android.resource://") == true
    return when (this) {
      StyleDisplay.LIST ->
        if (isAndroidResource) {
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM
        } else {
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        }
      StyleDisplay.GRID ->
        if (isAndroidResource) {
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM
        } else {
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        }
    }
  }

  /**
   * Content-style extras for [track] on a browse surface. [section] supplies the group title and,
   * from its style, the item's display hint: these extras are all of a section the flat
   * MediaBrowser protocol carries (ADR 0010). The track's own `display` never sets its item hint,
   * since display is positional (ADR 0011). A browsable track's declared `display` is the promise
   * for the page it opens, sent as the parent-level BROWSABLE/PLAYABLE hint, the only below-root
   * hint AOSP-derived AAOS media UIs honor.
   */
  fun build(track: Track, section: Section? = null): Bundle =
    build(
      groupTitle = section?.title,
      itemDisplay = section?.style?.display,
      promisedDisplay = if (track.src == null) track.style?.display else null,
      artwork = track.artwork?.url,
    )

  private fun build(
    groupTitle: String?,
    itemDisplay: StyleDisplay?,
    promisedDisplay: StyleDisplay?,
    artwork: String?,
  ): Bundle =
    Bundle().apply {
      groupTitle?.let {
        putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it)
      }
      itemDisplay?.let {
        putInt(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
          it.toContentStyleValue(artwork),
        )
      }
      promisedDisplay?.let {
        val styleValue = it.toContentStyleValue(artwork)
        putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, styleValue)
        putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, styleValue)
      }
    }
}
