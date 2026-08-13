package com.audiobrowser.extension

import com.audiobrowser.util.BrowserPathHelper
import com.margelo.nitro.audiobrowser.Track

/**
 * The queue position of the tapped surface, for skip-in-place. Exact-surface match first: a
 * contextual path carries the tapped page position (`__index`), so path equality pins the exact
 * copy when the page holds the same identity more than once. The identity match remains for
 * index-less paths (e.g. pre-stamp persisted state); an index-stamped path with no exact match
 * returns -1, and the caller falls through to expansion, which re-scopes the queue to the tapped
 * section (ADR 0009).
 */
fun Array<Track>.indexOfTappedTrack(contextualPath: String, trackId: String?): Int {
  val index = indexOfFirst { it.path == contextualPath }
  if (index >= 0 || BrowserPathHelper.extractIndex(contextualPath) != null || trackId == null) {
    return index
  }
  return indexOfFirst { it.identity == trackId }
}
