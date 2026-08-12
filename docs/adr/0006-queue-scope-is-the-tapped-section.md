# Queue scope is the tapped section

Selecting a playable track from a browse page expands the queue from the
_section_ the track sits in — its contiguous `groupTitle` run, or the image
row it belongs to — not from the page's full children. A page aggregating
several sections (regional picks below a city's stations, an image row above
a list) must not leak next/previous across them: the playback context a
listener expects is the list they tapped in. Image-row items are stamped
with contextual URLs at transform time like any list row, so thumbnail taps
flow through the same expansion instead of loading a queue of one.

## Considered options

- **Whole-page queue (previous behavior)** — next/previous wandered across
  unrelated sections; image-row taps didn't queue at all.
- **A per-route/per-section queue policy knob** — deferred until a surface
  actually varies (`singleTrack` already covers the opt-out extreme); its
  natural home is a `Section` property in the sectioned-model migration
  (issue #93), not a route-keyed setting on the flat model.

## Consequences

- A track id that no longer appears on the page aborts the expansion; the
  caller falls back to the stored single track. Both platforms agree on this
  now — iOS previously defaulted a vanished id to index 0, which could
  resume a _different_ station after the list changed (masked on recency
  lists, where the last-played track is always first).
- Section identity is still derived from `groupTitle` adjacency; #93 would
  make the derivation structural.
