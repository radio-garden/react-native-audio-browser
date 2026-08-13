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
     * of their own). [tappedOffset] is the tapped child's offset within the run — non-null only
     * when the stamped index pinned the exact copy.
     */
    data class Run(val tracks: List<Track>, val tappedOffset: Int? = null) : Section()
  }

  /**
   * The section of [children] containing the playable [trackId] (a track identity — id when
   * non-blank, else src), or null when the id is not found.
   *
   * [tappedIndex] — the page position stamped into the contextual URL — is a tie-breaker, never an
   * identifier: when the child at that position still carries the tapped identity (directly or in
   * its image row), it pins which surface was tapped; when it doesn't (the list shifted),
   * resolution falls back to the first identity match. A stale index can therefore never select a
   * different track — at worst a different copy of the same one.
   */
  fun section(children: List<Track>, trackId: String, tappedIndex: Int? = null): Section? {
    if (tappedIndex != null && tappedIndex in children.indices) {
      val child = children[tappedIndex]
      if (child.identity == trackId) {
        return run(children, tappedIndex, pinned = true)
      }
      val items = child.imageRow
      if (items != null && items.any { it.identity == trackId }) {
        return Section.ImageRow(items.toList())
      }
    }
    for (child in children) {
      val items = child.imageRow
      if (items != null && items.any { it.identity == trackId }) {
        return Section.ImageRow(items.toList())
      }
    }
    val index = children.indexOfFirst { it.identity == trackId }
    if (index < 0) return null
    return run(children, index, pinned = false)
  }

  private fun run(children: List<Track>, index: Int, pinned: Boolean): Section.Run {
    val group = children[index].groupTitle
    var start = index
    while (start > 0 && children[start - 1].groupTitle == group) start--
    var end = index
    while (end + 1 < children.size && children[end + 1].groupTitle == group) end++
    return Section.Run(
      children.subList(start, end + 1).toList(),
      tappedOffset = if (pinned) index - start else null,
    )
  }
}
