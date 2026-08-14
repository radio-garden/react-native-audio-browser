# The contextual path pins the tapped position

**Status:** accepted

Queue expansion scopes to the tapped section (ADR 0006), and sections are
located by track identity (ADR 0008) — so a page holding the same identity
in more than one place made expansion guess which surface was tapped, with
a fixed bias: an image row beat a flat-list duplicate, an earlier
`groupTitle` run beat a later one, and the first copy inside a section beat
the one actually tapped ([#94](https://github.com/radio-garden/react-native-audio-browser/issues/94)).
The contextual path only encoded `parentPath + __trackId`, which cannot
distinguish copies.

The browse pipeline now stamps the child's page position into the
contextual path — `{parentPath}?__trackId={identity}&__index={position}`;
an image-row item carries its **row's** position — and resolution uses it
as a tie-breaker, never an identifier. Identity stays the check at every
step:

- The child at the stamped index still carries the tapped identity
  (directly, or in its image row) → that position pins the section and the
  exact copy.
- It doesn't (the list shifted) → fall back to the first identity match —
  the pre-stamp behavior.
- The identity is gone from the page entirely → abort expansion,
  single-track fallback (unchanged from ADR 0006).

A stale index can therefore never select a different track — at worst a
different copy of the same one, which is exactly what the pre-stamp
behavior always did. The staleness window is also narrow: at tap time the
rendered rows and the expansion resolve against the same cached page
snapshot, so the stamp is fresh where the duplicate bugs fire; only
resumption and voice-initiated playback re-resolve against a possibly
changed page.

Skip-in-place rides along: when the queue came from the same page, the
tapped row is matched by full contextual path first (which carries the
stamp), so a duplicate-identity tap in another section re-expands and
re-scopes instead of silently skipping inside the old section's queue. The
bare identity match remains for index-less paths (pre-stamp persisted
state), which degrade to the old behavior, once.

## Considered options

- **Stamp a section ordinal** — pins the section but not the copy within
  it; the item index gives both, and the section is derivable from it.
- **Abort expansion on a stale index** (this decision's first framing) —
  rejected: an index mismatch alone must not abort playback of a track
  that is still on the page. Aborting stays reserved for a vanished
  identity.
- **Structural sections ([#93](https://github.com/radio-garden/react-native-audio-browser/issues/93))**
  — would make section identity declared rather than derived, but doesn't
  pin the copy within a section. Complementary: if it lands, the stamp
  keeps doing the within-section work.

## Consequences

- The guide constraint "a src should appear in at most one section per
  page" is lifted — a tap resolves to the section it happened in. The old
  precedence order still governs index-less paths and stale stamps.
- If the page was reordered between stamping and resumption, "which copy's
  section was tapped" is unrecoverable — that information no longer exists
  in the new list; no stamping scheme fixes it. Resolution degrades to the
  first identity match.
- The car now-playing row indicator still matches by identity alone, so
  duplicate copies of the playing track all light up. Android Auto's
  indicator is mediaId-driven and the stable mediaId is deliberately
  context-free (see `TrackFactory.buildMediaItem`) — pinning the indicator
  would break the cross-tab match that design buys.
- On Android Auto, a tap on an id-bearing duplicate round-trips only the
  context-free stable mediaId, which resolves through the track cache to
  the _last-browsed_ copy's contextual path — the tapped copy is not
  recoverable from the wire. In-app taps, CarPlay, and id-less tracks
  (contextual mediaIds) carry the exact stamp.

_Amended by ADR 0010: with structural sections, `__index` is the flat
position over the page's flattened sections (children concatenated in
section order), rail items carry their own positions (removing the
within-row residual below), and the old image-row/run precedence survives
only as the index-less fallback (first section containing the identity)._
