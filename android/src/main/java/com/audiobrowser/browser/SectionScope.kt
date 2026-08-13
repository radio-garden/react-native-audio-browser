package com.audiobrowser.browser

import com.audiobrowser.extension.identity
import com.margelo.nitro.audiobrowser.ImageRowItem
import com.margelo.nitro.audiobrowser.Track

/**
 * Queue scope is the tapped section, not the whole page (ADR 0006): the playback context a listener
 * expects is the list they tapped in — a page aggregating several sections must not leak
 * next/previous across them.
 */
object SectionScope {
  sealed class Section {
    /** The tapped id lives inside an image row's items. */
    data class ImageRow(val items: List<ImageRowItem>) : Section()

    /**
     * The contiguous `groupTitle` run around the tapped child (items with no group title form runs
     * of their own).
     */
    data class Run(val tracks: List<Track>) : Section()
  }

  /**
   * The section of [children] containing the playable [trackId] (a track identity — id when
   * non-blank, else src), or null when the id is not found.
   */
  fun section(children: List<Track>, trackId: String): Section? {
    for (child in children) {
      val items = child.imageRow
      if (items != null && items.any { it.identity == trackId }) {
        return Section.ImageRow(items.toList())
      }
    }
    val index = children.indexOfFirst { it.identity == trackId }
    if (index < 0) return null
    val group = children[index].groupTitle
    var start = index
    while (start > 0 && children[start - 1].groupTitle == group) start--
    var end = index
    while (end + 1 < children.size && children[end + 1].groupTitle == group) end++
    return Section.Run(children.subList(start, end + 1).toList())
  }
}
