package com.audiobrowser.util

import android.os.Bundle
import androidx.media.utils.MediaConstants
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.StyleDisplay
import com.margelo.nitro.audiobrowser.Track

/** Builds MediaMetadata extras bundle for Android Auto/AAOS content styling. */
object MediaExtrasBuilder {

  /**
   * Maps a display mode to the MediaConstants content style value. When artwork is an
   * android.resource:// URI, uses CATEGORY_* variants which add margins around icons (better for
   * small vector icons).
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
   * Extras for a track rendered on a browse surface. The owning [section] supplies the Android Auto
   * group-title header hint (grouping is a per-item advisory in the flat MediaBrowser protocol —
   * the section dies here, ADR 0010) and, from its page-folded style block, the per-item display
   * hint: per-item hints derive from the section resolution, never from the track's own `display`,
   * which is positional (ADR 0011).
   *
   * The track's own declared `display` is the page-layout *promise* for the page a browsable track
   * opens — emitted as the parent-level BROWSABLE/PLAYABLE hint, the only below-root hint
   * AOSP-derived AAOS media UIs honor. Declared or it doesn't exist: the library never derives a
   * promise from pages it hasn't resolved, and never back-fills from ones it has.
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
