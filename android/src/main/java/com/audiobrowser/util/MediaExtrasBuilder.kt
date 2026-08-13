package com.audiobrowser.util

import android.os.Bundle
import androidx.media.utils.MediaConstants
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.SectionStyle
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackStyle

/** Builds MediaMetadata extras bundle for Android Auto/AAOS content styling. */
object MediaExtrasBuilder {

  /**
   * Maps TrackStyle to MediaConstants content style value. When artwork is an android.resource://
   * URI, uses CATEGORY_* variants which add margins around icons (better for small vector icons).
   *
   * @see <a href="https://developer.android.com/training/cars/media#default-content-style">Default
   *   content style</a>
   */
  private fun TrackStyle.toContentStyleValue(artwork: String?): Int {
    val isAndroidResource = artwork?.startsWith("android.resource://") == true
    return when (this) {
      TrackStyle.LIST ->
        if (isAndroidResource) {
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM
        } else {
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        }
      TrackStyle.GRID ->
        if (isAndroidResource) {
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM
        } else {
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        }
    }
  }

  /**
   * The per-item style hint a section pushes down onto its children (ADR 0010): tile sections
   * render as grid items (Android Auto's grid always wraps, so `grid` and `grid-row` are the same
   * hint here); `list` renders as list rows. An explicit per-track `style` is more specific and
   * wins.
   */
  private fun SectionStyle.toTrackStyle(): TrackStyle =
    when (this) {
      SectionStyle.LIST -> TrackStyle.LIST
      SectionStyle.GRID,
      SectionStyle.GRID_ROW -> TrackStyle.GRID
    }

  /**
   * Extras for a track rendered on a browse surface. The owning [section] supplies the Android Auto
   * group-title header hint (grouping is a per-item advisory in the flat MediaBrowser protocol —
   * the section dies here, ADR 0010) and the style default.
   */
  fun build(track: Track, section: Section? = null): Bundle =
    build(
      groupTitle = section?.title,
      style = track.style ?: section?.style?.toTrackStyle(),
      childrenStyle = track.childrenStyle,
      artwork = track.artwork?.url,
    )

  private fun build(
    groupTitle: String?,
    style: TrackStyle?,
    childrenStyle: TrackStyle?,
    artwork: String?,
  ): Bundle =
    Bundle().apply {
      groupTitle?.let {
        putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it)
      }
      style?.let {
        putInt(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
          it.toContentStyleValue(artwork),
        )
      }
      childrenStyle?.let {
        val styleValue = it.toContentStyleValue(artwork)
        putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, styleValue)
        putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, styleValue)
      }
    }
}
