# A preview section queues its linked page

**Status:** accepted

A **Section** may declare `queue: 'path'`. A tap on one of its tracks then queues the page its `path` links to, the section there that holds the tapped track, instead of the section's own `children`. Without the field, or with `queue: 'section'`, the section queues its own children (ADR 0006). This is the per-section queue policy ADR 0006 deferred and ADR 0010 parked, with only the value a surface needs today.

It is decided when the page is stamped: each child's contextual path names `section.path` as its parent instead of the page it sits on, and carries the identity without an `__index`. The expansion, resumption and skip-in-place machinery is unchanged. A tap in the preview and a tap on the linked page queue from the same `queueSourcePath`, so either one skips in place in the other's queue while the page's positions hold. Expanding the linked page leaves Android's track cache pointing at the page the listener browsed: a car taps by id, and the next tap must still find the list it came from.

## Why

- **A preview is a slice of a longer list.** A rail of six recent stations with a "view all" link stands for the whole list, and a listener who taps the third expects next to reach the seventh. Queueing the six makes the same station give two different queues, depending on where it was tapped.
- **The server can't send the full list in `children`.** Android Auto shows every child, and CarPlay counts each one against the template's item cap, so the preview's size is how much the car shows.

## Considered options

- **Queue the section's own children (the default stays)**: uniform, offline-safe, no added latency on a tap.
- **Queue the `path` page whenever a section has one**: rejected, since it ties what plays to where a header leads, and puts a request in front of every tap on any section with a link.
- **Default rails to `'path'`**: rejected, since it ties `queue` to `style`, which affects nothing but presentation.
- **`'page'` and `'track'`**: no surface needs them yet. They stay in FUTURE.md.

## Consequences

- **A tap can wait on a request.** The linked page is resolved at tap time. When it was browsed before, it comes from the cache.
- **Identity alone finds the track.** The `__index` counts positions on the preview's page, not the linked one, so it is left out. A duplicate identity on the linked page queues from its first copy (ADR 0009's fallback).
- **The preview and the page can drift.** When the linked page no longer holds the tapped track, or it doesn't resolve, the vanished-identity rule plays the stored single track (ADR 0006).
- **A `path`-queued section without a `path` queues itself.**
