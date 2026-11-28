package com.audiobrowser.browser

import com.audiobrowser.extension.identity
import com.audiobrowser.util.BrowserPathHelper
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.Track

/**
 * The queue is the tapped section, not the whole page, so next/previous never crosses sections
 * (docs/adr/0006-queue-scope-is-the-tapped-section.md).
 */
object SectionScope {
  /**
   * [tappedOffset] is the tapped child's index in [section], null when no index pinned it.
   * [onlyDisabled] is set when no index pinned it and the page holds the identity only disabled.
   */
  data class Scoped(
    val section: Section,
    val tappedOffset: Int?,
    val onlyDisabled: Boolean = false,
  )

  /**
   * The section of [sections] holding [trackId] (a track identity), or null when none does.
   *
   * [tappedIndex] is the flat position stamped into the contextual path (children concatenated in
   * section order). It only breaks ties: when the child there still has [trackId], it pins that
   * section and copy; otherwise (the list shifted, or the index is out of range) the first section
   * holding an enabled copy of [trackId] is returned, unpinned, else the first holding any. A stale
   * index never selects a different track.
   */
  fun scoped(sections: List<Section>, trackId: String, tappedIndex: Int? = null): Scoped? {
    if (tappedIndex != null && tappedIndex >= 0) {
      var start = 0
      for (section in sections) {
        val offset = tappedIndex - start
        if (offset < section.children.size) {
          if (section.children[offset].identity == trackId) {
            return Scoped(section, offset)
          }
          break
        }
        start += section.children.size
      }
    }
    val holding = sections.filter { s -> s.children.any { it.identity == trackId } }
    holding
      .firstOrNull { s -> s.children.any { it.identity == trackId && it.disabled != true } }
      ?.let {
        return Scoped(it, null)
      }
    return holding.firstOrNull()?.let { Scoped(it, null, onlyDisabled = true) }
  }

  /**
   * The queue a tap on the contextual [path] plays: the tapped section's playable tracks, at the
   * pinned copy, else the first enabled identity match; only that track when [singleTrack]. Null
   * when the identity is gone from the page or the tapped track is not playable.
   *
   * @throws DisabledOnLinkedPageException when [path] is linked and the page holds the identity
   *   only disabled
   */
  fun queue(sections: List<Section>, path: String, singleTrack: Boolean): Pair<Array<Track>, Int>? {
    val trackId = BrowserPathHelper.extractTrackId(path) ?: return null
    val scoped = scoped(sections, trackId, BrowserPathHelper.extractIndex(path)) ?: return null
    if (scoped.onlyDisabled && BrowserPathHelper.extractOrigin(path) != null) {
      throw DisabledOnLinkedPageException(trackId)
    }
    val children = scoped.section.children
    val playable = children.filter { it.isPlayable }
    val offset = scoped.tappedOffset
    val selectedIndex =
      if (offset != null && children[offset].isPlayable) {
        children.take(offset + 1).count { it.isPlayable } - 1
      } else {
        playable.indexOfFirst { it.identity == trackId }.takeIf { it >= 0 } ?: return null
      }
    return if (singleTrack) Pair(arrayOf(playable[selectedIndex]), 0)
    else Pair(playable.toTypedArray(), selectedIndex)
  }

  /**
   * What a restore of the contextual [path]'s [track] loads from its page's [sections]: the queue
   * [queue] gives; with [insertGone], when the identity is gone from the page, [track] ahead of the
   * playable tracks of [restoreSection]. Refused when the page holds the identity only disabled,
   * pinned or not; unavailable when the identity is gone and [insertGone] is off, or the page holds
   * it enabled but would not queue it (no `src`).
   *
   * @param stored the identities of the stored queue [track] is current in, empty when none is
   */
  fun restoreQueue(
    sections: List<Section>,
    path: String,
    track: Track,
    stored: Set<String>,
    insertGone: Boolean,
    singleTrack: Boolean,
  ): RestoreExpansion {
    val trackId = BrowserPathHelper.extractTrackId(path)
    if (trackId == null || sections.isEmpty()) return RestoreExpansion.Unavailable
    val source = BrowserPathHelper.stripTrackId(path)
    val copies = sections.flatMap { s -> s.children.filter { it.identity == trackId } }
    if (copies.isEmpty()) {
      if (!insertGone) return RestoreExpansion.Unavailable
      val section = restoreSection(sections, BrowserPathHelper.extractIndex(path), stored)
      val tracks =
        if (singleTrack) arrayOf(track)
        else arrayOf(track) + section.children.filter { it.isPlayable }
      return RestoreExpansion.Queue(ExpandedQueue(tracks, 0, source))
    }
    val tapped =
      try {
        queue(sections, path, singleTrack)
      } catch (_: DisabledOnLinkedPageException) {
        null
      }
    if (tapped != null)
      return RestoreExpansion.Queue(ExpandedQueue(tapped.first, tapped.second, source))
    return if (copies.all { it.disabled == true }) RestoreExpansion.Refused
    else RestoreExpansion.Unavailable
  }

  /**
   * The section a track gone from the page goes into: the one sharing the most identities with
   * [storedQueue], else the one the stamped [index] falls in, clamped to the last, else the first.
   * The index only approximates: the page shifted when the track went.
   */
  private fun restoreSection(
    sections: List<Section>,
    index: Int?,
    storedQueue: Set<String>,
  ): Section {
    sections
      .map { section -> section to section.children.count { it.identity in storedQueue } }
      .filter { (_, shared) -> shared > 0 }
      .maxByOrNull { (_, shared) -> shared }
      ?.let {
        return it.first
      }
    if (index != null && index >= 0) {
      var remaining = index
      for (section in sections) {
        if (remaining < section.children.size) return section
        remaining -= section.children.size
      }
      return sections.last()
    }
    return sections.first()
  }

  private val Track.isPlayable: Boolean
    get() = src != null && disabled != true
}

// Page shape helpers (ADR 0010)

/** One untitled section: how a flat track list (`children` sugar, search results) is wrapped. */
fun untitledSection(children: Array<Track>): Section =
  Section(title = null, style = null, path = null, queue = null, children = children)

/** The page as sections: `sections` when present, else `children` as one untitled section. */
val ResolvedTrack.normalizedSections: List<Section>?
  get() = sections?.toList() ?: children?.let { listOf(untitledSection(it)) }

/**
 * [normalizedSections] with the page's style folded into each section's, property by property (ADR
 * 0011), so callers see only effective section styles.
 */
fun ResolvedTrack.styleResolvedSections(): List<Section>? =
  normalizedSections?.map { it.copy(style = StyleResolver.sectionStyle(it.style, style)) }

/** The page's children in section order: the positions a contextual `__index` counts in. */
val ResolvedTrack.flattenedChildren: List<Track>?
  get() = normalizedSections?.flatMap { it.children.asList() }
