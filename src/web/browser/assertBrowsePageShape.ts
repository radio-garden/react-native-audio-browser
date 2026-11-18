import type { ResolvedTrack } from '../../types'

/**
 * Guards the cast of a browse endpoint's body to a page object. Catches the
 * most common shape mistake — returning a bare Track[] — with an actionable
 * message instead of a blank screen downstream. Browse, tabs, and search
 * endpoints all return the page shape; only `children`'s meaning differs.
 */
export function assertBrowsePageShape(
  response: unknown,
  path: string
): ResolvedTrack {
  if (Array.isArray(response)) {
    throw new Error(
      `Browse endpoint for "${path}" returned a JSON array; expected a ` +
        `page object { title, sections?: Section[], children?: Track[] }. ` +
        `Wrap the rows in a \`children\` array (one untitled section) or ` +
        `group them into \`sections\`.`
    )
  }
  if (response === null || typeof response !== 'object') {
    throw new Error(
      `Browse endpoint for "${path}" returned ${JSON.stringify(response)}; ` +
        `expected a page object { title, sections?: Section[], ` +
        `children?: Track[] }.`
    )
  }
  return response as ResolvedTrack
}
