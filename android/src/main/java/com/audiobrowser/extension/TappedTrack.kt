package com.audiobrowser.extension

import com.audiobrowser.util.BrowserPathHelper
import com.margelo.nitro.audiobrowser.Track

/**
 * The queue index of the tapped track, for skipping in place, or -1. An exact path match wins,
 * since the path's `__index` tells apart copies of the same identity. Only an index-less path falls
 * back to matching [trackId]; an index-stamped path with no exact match returns -1, so the caller
 * re-expands the queue to the tapped section (ADR 0009).
 */
fun Array<Track>.indexOfTappedTrack(contextualPath: String, trackId: String?): Int {
  val index = indexOfFirst { it.path == contextualPath }
  if (index >= 0 || BrowserPathHelper.extractIndex(contextualPath) != null || trackId == null) {
    return index
  }
  return indexOfFirst { it.identity == trackId }
}
