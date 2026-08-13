package com.audiobrowser.browser

import com.audiobrowser.extension.identity
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.Track

/**
 * Queue scope is the tapped section, not the whole page (ADR 0006): the playback context a listener
 * expects is the list they tapped in — a page aggregating several sections must not leak
 * next/previous across them. Sections are structural (ADR 0010), so scoping is a lookup, not a
 * groupTitle-run derivation.
 */
object SectionScope {
  /**
   * The section containing the tapped identity, plus the tapped child's offset within it — non-null
   * only when the stamped flat index pinned the exact copy.
   */
  data class Scoped(val section: Section, val tappedOffset: Int?)

  /**
   * The section of [sections] containing the playable [trackId] (a track identity — id when
   * non-blank, else src), or null when not found.
   *
   * [tappedIndex] — the flat page position stamped into the contextual URL (children concatenated
   * in section order) — is a tie-breaker, never an identifier: when the child at that position
   * still carries the tapped identity, it pins which section (and which copy) was tapped; when it
   * doesn't (the list shifted), resolution falls back to the first section containing the identity.
   * A stale index can therefore never select a different track — at worst a different copy of the
   * same one.
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
    for (section in sections) {
      if (section.children.any { it.identity == trackId }) {
        return Scoped(section, null)
      }
    }
    return null
  }
}

// Page shape helpers (ADR 0010)

/**
 * One untitled section — the canonical wrap of a flat track list (`children` authoring sugar,
 * search results — ADR 0010).
 */
fun untitledSection(children: Array<Track>): Section =
  Section(title = null, subtitle = null, style = null, path = null, children = children)

/**
 * The canonical sectioned shape: `sections` wins when present; plain `children` is authoring sugar
 * for one untitled section.
 */
val ResolvedTrack.normalizedSections: List<Section>?
  get() = sections?.toList() ?: children?.let { listOf(untitledSection(it)) }

/**
 * The page's children concatenated in section order — the flattening that defines contextual
 * `__index` positions and the flat views (tabs, search) of a sectioned page.
 */
val ResolvedTrack.flattenedChildren: List<Track>?
  get() = normalizedSections?.flatMap { it.children.asList() }
