package com.audiobrowser.util

import android.os.Bundle
import androidx.media.utils.MediaConstants
import com.margelo.nitro.audiobrowser.ResolvedTrack
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

  fun build(track: Track): Bundle =
    build(
      groupTitle = track.groupTitle,
      style = track.style,
      childrenStyle = track.childrenStyle,
      artwork = track.artwork,
    )

  fun build(resolvedTrack: ResolvedTrack): Bundle =
    build(
      groupTitle = resolvedTrack.groupTitle,
      style = resolvedTrack.style,
      childrenStyle = resolvedTrack.childrenStyle,
      artwork = resolvedTrack.artwork,
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
