package com.audiobrowser.extension

import com.audiobrowser.util.BrowserPathHelper
import com.margelo.nitro.audiobrowser.Track

/**
 * The queue index of the tapped track, for skipping in place, or -1. An exact path match wins,
 * since the path's `__index` tells apart copies of the same identity. An index-less path falls back
 * to matching [trackId]; an index-stamped path with no exact match matches only the track at
 * [currentIndex], which the page may have moved, and otherwise returns -1, so the caller re-expands
 * the queue to the tapped section (ADR 0009).
 */
fun Array<Track>.indexOfTappedTrack(
  contextualPath: String,
  trackId: String?,
  currentIndex: Int?,
): Int {
  val index = indexOfFirst { it.path == contextualPath }
  if (index >= 0 || trackId == null) return index
  if (BrowserPathHelper.extractIndex(contextualPath) == null) {
    return indexOfFirst { it.identity == trackId }
  }
  return currentIndex?.takeIf { getOrNull(it)?.identity == trackId } ?: -1
}
