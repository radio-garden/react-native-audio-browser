/**
 * A track's identity: the opaque `id` when present (non-blank), else the
 * playable `src`. Two tracks refer to the same item iff their identities are
 * equal. Browsable-only tracks (neither `id` nor `src`) have no identity —
 * they are addressed by `path` instead.
 *
 * This is THE comparison rule for favorites matching, section scoping,
 * skip-in-place, the car now-playing row indicator, and the contextual
 * `__trackId` — see ADR 0008.
 */
export function trackIdentity(track: {
  id?: string
  src?: string
}): string | undefined {
  const id = track.id
  if (id !== undefined && id !== '') return id
  return track.src
}
