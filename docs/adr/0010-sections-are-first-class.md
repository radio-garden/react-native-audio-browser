# Sections are first-class

**Status:** accepted

A resolved browse page is a flat `children: Track[]`; grouping is encoded by
adjacency (contiguous `groupTitle` runs), and an image row is a pseudo-track
carrying an `imageRow: ImageRowItem[]` payload. Every layer then re-derives
or destroys structure: CarPlay scans runs to build `CPListSection`s,
`SectionScope` scans the same runs to scope the queue (ADR 0006), Android
Auto re-encodes the run as a per-item extras hint, and consumers whose
upstream page models are already sectioned must flatten to adjacency only
for the library to guess the sections back
([#93](https://github.com/radio-garden/react-native-audio-browser/issues/93)).

Sections become a real type instead:

```ts
interface Section {
  /** Header text. Absent = headerless group. */
  title?: string
  /** Secondary line for the section's navigation surface ("view all"). */
  subtitle?: string
  /** How children render: list rows (default), wrapping tiles, or one line of tiles. */
  style?: 'list' | 'grid' | 'rail'
  /** Navigation target for the section header / "view all" surface. */
  path?: string
  children: Track[]
}

// ResolvedTrack.sections?: Section[]   — canonical resolved shape
// ResolvedTrack.children?: Track[]     — authoring sugar: one untitled section
```

Decisions, each with its reason:

- **`sections` is the canonical resolved shape; `children` stays as input
  sugar.** A browse source may still declare a plain `children: Track[]` —
  accepted at both decode boundaries (the TS configuration and the JSON
  wire) and normalized to a single untitled section, so flat pages
  (favorites, search-like lists) stay flat to author. Resolved output
  always carries `sections`; `children` is never populated on output, so
  there is exactly one structure to hydrate, transform, and scope against.
- **`groupTitle`, `imageRow`, and `ImageRowItem` are deleted, not shimmed.**
  No consumer has shipped to production, so the compatibility phase the
  original issue planned for is unnecessary. `ImageRowItem` was already a
  strict subset of `Track`. Legacy fields in JSON payloads decode as
  ignored dead weight — silently; the consumer migration is lockstep, so
  there is no one left to warn.
- **The tile styles are named for layout, not for a platform class.**
  `'grid'` wraps to as many lines as needed; `'rail'` is exactly one
  line of tiles (what the flat model called an image row — a name that was
  `CPListImageRowItem` leaking into the domain, the same mistake this ADR
  retires `groupTitle` for). Style names declare the _requested_ layout; a
  platform renders its nearest supported form. Android Auto has no
  single-line tile container, so `rail` renders there as `grid` —
  showing more, never less. If styles could only name what every surface
  guarantees, the vocabulary would be exactly `'list'`.
- **A `style` value must earn entry** by rendering sanely on all three
  surfaces (app UI, CarPlay, Android Auto). Platform-exclusive
  presentations (CarPlay cards, condensed rows, grid-template pages) are
  not styles — if ever needed they arrive as explicitly platform-scoped
  hints, never as core vocabulary. This is the guardrail against the
  unified model chasing per-platform divergence.
- **Queue scope is declared, never rendered.** A section's queue is its
  `children`, identical on every platform, screen width, and resumption
  path. Rendering may truncate what is _visible_ (CarPlay fits a
  width-dependent number of tiles per row and exposes no way to query it);
  it never changes what _plays_.
- **The per-section queue policy stays deferred.** It is additive — an
  optional field lands compatibly at any time, even post-ship — so it does
  not spend this migration's breaking-change window. ADR 0006's discipline
  holds: the knob arrives when a surface actually varies. The sketch
  (including linked-page queueing, where a preview section queues its
  `path` target's content) is parked in FUTURE.md.
- **The contextual `__index` stays a flat position** over the page's
  flattened sections (children concatenated in section order). The URL
  format, `BrowserPathHelper`, skip-in-place, and persisted paths from
  ADR 0009 are untouched; resolution maps the flat index to its owning
  section instead of deriving a run. Grid-row items gain their own flat
  positions (today they share their row's), which also removes the
  within-row duplicate residual noted in ADR 0009.
- **Flat surfaces stay flat.** Tabs, search results, and the queue remain
  `Track[]` — they are lists, not pages. A search page is internally a
  single untitled section.
- **Android Auto flattens at the Media3 boundary, nowhere else.** The
  MediaBrowser protocol has no section node — grouping is an advisory
  per-item extras hint that requires contiguity, which is exactly what
  adjacency encoded. `MediaSessionCallback.toMediaItems` becomes
  `[Section] → [MediaItem]`: it stamps each child with its owning section's
  title/style and appends a "view all" link from `section.path`/`subtitle`.
  The wire behavior car clients see is unchanged.
- **CarPlay maps sections 1:1 to `CPListSection`.** The run-accumulation
  scan is deleted; the section/item budget-truncation logic survives as-is.
  A `rail` section renders as a _headerless_ `CPListSection` holding
  one image-row item whose text is `section.title` (today's exact look). A
  `grid` section renders as a wrapping, titled tile grid on iOS 26+
  (`imageGridElements` + `allowsMultipleLines` — never the title-less
  `gridElements`, so artwork-less tracks keep their name on screen) and
  degrades to a list section before iOS 26, where the only tile container
  truncates at an unknowable width. Tile styles presume artwork:
  artwork-less children render a placeholder tile plus title, and the
  artwork `resolve` hook is the consumer's place to supply fallback art —
  the library does not invent content.
- **`section.style` wins over `childrenStyle` where set.** The browsable
  row's `childrenStyle` remains the drilled-into page's default; a
  section's style is pushed down as per-item hints, which the platform
  already lets override the default — the only order that lets one page
  mix styled and unstyled sections.
- **`SectionScope` reduces to a lookup.** The tapped section is the owner
  of the flat index (identity-checked per ADR 0009); the groupTitle
  run-walking and the image-row special case are deleted.
- **The web stub gains section scoping.** It currently queues the whole
  page, contradicting ADR 0006; with sections structural, the web
  implementation scopes identically to native.

## Considered options

- **Additive migration (`sections` alongside populated `children`)** — the
  original issue's plan, written when shipped consumers were assumed.
  Rejected while the no-production-consumers window is open: two populated
  shapes must be kept coherent through hydration and transforms, and the
  window is the cheapest moment this change will ever have.
- **Keep `groupTitle` as an input dialect** (adjacency auto-grouped into
  sections) — rejected; it preserves the fragile encoding this ADR removes
  and makes section identity ambiguous when both dialects appear.
- **Per-platform section vocabularies** (declare CarPlay and Android Auto
  layouts separately instead of one model) — rejected. The content comes
  from one server that cannot reasonably emit per-platform trees; the
  mapping tables don't disappear, they move into every consumer, untested.
  `Section` models the editorial 90% both platforms natively share (titled
  groups of tappable tracks); the style-admission rule fences off the
  divergent 10%.
- **`(section, item)` pair in the contextual URL** — rejected; a flat index
  carries the same information once section order defines the flattening,
  and keeps ADR 0009's format and fallbacks byte-compatible.
- **Sectioned tabs/search** — rejected; nothing renders sections there.

## Consequences

- `Track` loses `groupTitle` and `imageRow`; `ImageRowItem` disappears from
  the spec. Every hand-written positional construction of the Nitro structs
  breaks at compile time — which is the enumeration mechanism for the
  migration, not an incident.
- Persisted playback state is track-level and unaffected.
- ADR 0006's queue scoping becomes declared rather than derived; ADR 0009's
  index tie-breaker survives unchanged and loses its rail residual.
- Reordering a page can no longer split or merge groups, and section
  identity is structural rather than a localized display string.
- A `rail` that can be swiped sideways exists on no car surface —
  CarPlay templates have no horizontal scrolling and Android Auto wraps;
  only app UIs may render it as a scroller. Authoring guidance: keep
  rails small and give them a `path` escape hatch.

---

**Amendment (August 2026, ADR 0011):** the `style` string vocabulary above
(`'list' | 'grid' | 'rail'`) is superseded — `Section.style` is now a
declaration block (`SectionStyle`), `'rail'` became `display: 'grid'` +
`gridWrap: false`, and the rail authoring guidance moves to the block's
docs. Sections as first-class structure, declared queue scoping, and the
flat wire are unchanged. See
[ADR 0011](0011-style-is-a-declaration-block.md).
