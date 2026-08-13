/**
 * A track's identity: its opaque `id` when present (non-blank), falling back
 * to the playable `src`. Two tracks refer to the same item exactly when their
 * identities are equal. Browsable-only tracks (neither `id` nor `src`) have
 * no identity — they are addressed by their `path` instead.
 *
 * This is the same comparison the library uses everywhere it matches tracks:
 * favorites (`setFavorites`), the CarPlay / Android Auto "now playing" row
 * indicator, and queue expansion and in-place skipping when a track is
 * selected from browse content. Use it for your own bookkeeping — keying a
 * favorites collection, deduplicating history — so your comparisons agree
 * with the library's.
 */
// Decision record: ADR 0008 (docs/adr/).
export function getTrackIdentity(track: {
  id?: string
  src?: string
}): string | undefined {
  return track.id?.length ? track.id : track.src
}
