import type { ResolvedTrack, Section, Track } from '../../types'
import { getTrackIdentity } from '../../utils/getTrackIdentity'

/**
 * Normalizes a resolved page to the canonical sectioned shape (ADR 0010):
 * `sections` wins when present; plain `children` is authoring sugar for one
 * untitled section. The output never carries `children` — there is exactly
 * one structure downstream code hydrates, transforms, and scopes against.
 */
export function normalizePage(content: ResolvedTrack): ResolvedTrack {
  const { children, sections, ...rest } = content
  const normalized =
    sections ?? (children !== undefined ? [{ children }] : undefined)
  return { ...rest, sections: normalized }
}

/**
 * The page's children concatenated in section order — the flattening that
 * defines the contextual `__index` positions (ADR 0009/0010) and the flat
 * views (tabs, search) of a sectioned page.
 */
export function flattenSections(sections: Section[] | undefined): Track[] {
  return sections?.flatMap((section) => section.children) ?? []
}

/**
 * The page's tracks as one flat list, normalizing first — the flat view
 * (tabs, search) of a page in either authored shape. Mirrors the native
 * `flattenedChildren` accessors.
 */
export function flattenedChildren(page: ResolvedTrack): Track[] {
  return flattenSections(normalizePage(page).sections)
}

/**
 * The section of the page containing the playable `trackId` (a track
 * identity: id when non-blank, else src), or undefined when not found.
 *
 * `tappedIndex` — the flat page position stamped into the contextual URL —
 * is a tie-breaker, never an identifier: when the child at that position
 * still carries the tapped identity, it pins which section (and which copy,
 * via `tappedOffset`) was tapped; when it doesn't (the list shifted),
 * resolution falls back to the first section containing the identity. A
 * stale index can therefore never select a different track — at worst a
 * different copy of the same one.
 */
export function sectionContaining(
  sections: Section[],
  trackId: string,
  tappedIndex: number | undefined
): { tracks: Track[]; tappedOffset?: number } | undefined {
  if (tappedIndex !== undefined && tappedIndex >= 0) {
    let start = 0
    for (const section of sections) {
      const offset = tappedIndex - start
      if (offset < section.children.length) {
        const tapped = section.children[offset]
        if (tapped && getTrackIdentity(tapped) === trackId) {
          return { tracks: section.children, tappedOffset: offset }
        }
        break
      }
      start += section.children.length
    }
  }
  for (const section of sections) {
    if (section.children.some((track) => getTrackIdentity(track) === trackId)) {
      return { tracks: section.children }
    }
  }
  return undefined
}
