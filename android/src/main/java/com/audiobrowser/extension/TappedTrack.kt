package com.audiobrowser.extension

import com.audiobrowser.util.BrowserPathHelper
import com.margelo.nitro.audiobrowser.Track

/**
 * The index a tap on [contextualPath] lands on, or -1. The exact path wins, pinning the tapped copy
 * when a page lists one track twice; [trackId] is matched by identity only when the path carries no
 * `__index`, because an indexed path with no exact match came from another section and needs the
 * queue rebuilt (ADR 0009).
 */
fun Array<Track>.indexOfTappedTrack(contextualPath: String, trackId: String?): Int {
  val index = indexOfFirst { it.path == contextualPath }
  if (index >= 0 || BrowserPathHelper.extractIndex(contextualPath) != null || trackId == null) {
    return index
  }
  return indexOfFirst { it.identity == trackId }
}
