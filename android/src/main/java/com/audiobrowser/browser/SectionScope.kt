package com.audiobrowser.browser

import com.audiobrowser.extension.identity
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.Track

/** The queue is the tapped section, not the whole page (ADR 0006). */
object SectionScope {
  data class Scoped(val section: Section, val tappedOffset: Int?)

  /**
   * The section of [sections] holding the track identity [trackId], or null when none does.
   *
   * [tappedIndex] is the flat position stamped into the contextual path, and only breaks ties: when
   * the child there still has [trackId], it pins that section and copy; otherwise the first section
   * holding [trackId] is returned, unpinned. A stale index never selects a different track.
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

fun untitledSection(children: Array<Track>): Section =
  Section(title = null, style = null, path = null, children = children)

val ResolvedTrack.normalizedSections: List<Section>?
  get() = sections?.toList() ?: children?.let { listOf(untitledSection(it)) }

fun ResolvedTrack.styleResolvedSections(): List<Section>? =
  normalizedSections?.map { it.copy(style = StyleResolver.sectionStyle(it.style, style)) }

/** The page's children in section order: the positions a contextual path's index counts in. */
val ResolvedTrack.flattenedChildren: List<Track>?
  get() = normalizedSections?.flatMap { it.children.asList() }
