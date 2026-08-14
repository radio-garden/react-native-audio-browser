# Style is a declaration block

**Status:** accepted

Presentation had been accreting as flat fields in two competing shapes: core
style strings (`Section.style: 'list' | 'grid' | 'rail'`,
`Track.style`/`childrenStyle`) and platform-prefixed hints
(`artworkCarPlayTinted`, with `carPlayImageShape`, `carPlayCardTint`,
`carPlayIsEnabled` and more designed on the same pattern). Every new knob
re-litigated the same questions — core or hint, section or track, which
prefix — and the answers were accumulating as per-field conventions rather
than a system.

Presentation becomes one CSS-adjacent **declaration block** instead:

```ts
interface TrackStyle {
  display?: 'list' | 'grid'          // positional: MY children (never inherited)
  accessorySymbol?: string           // ┐ inherited item properties:
  artworkRendering?: 'original' | 'stencil'  // │ track ?? section ?? page ?? default
  imageShape?: 'circular' | 'rounded-rectangle' // │
  cardTint?: string                  // │
  cardImage?: 'normal' | 'background' // ┘
}
interface SectionStyle extends TrackStyle {
  gridWrap?: boolean                 // ┐ container properties (scope override):
  gridTile?: 'plain' | 'card' | 'condensed' // ┘ section ?? page ?? default
}

Section.style?: SectionStyle
Track.style?: TrackStyle
ResolvedTrack.style?: SectionStyle   // a page is a Track that is also the container
```

The rules, once:

- **Declarations are aspirational.** Each surface renders the properties it
  understands and ignores the rest — inert, never an error. A dev-mode
  diagnostic recovers what compile-time gating would have caught.
- **Placement is mechanical.** An Apple element _parameter_ (a knob on one
  tile) is an inherited item property; an element _class_ or item-level knob
  (a fact about the container) is a container property.
- **`display` is positional** — CSS's non-inherited inner display type:
  each holder declares its own children's layout. The page declares for
  its whole scope and a section overrides it for its own children — a
  scope override between two declarations about the same decision, never
  inheritance or fallback. A browsable handle's `display` is the
  page-layout _promise_ that feeds Android Auto's parent-level hint, the
  only below-root hint AOSP-derived AAOS media UIs honor. Declared or it
  doesn't exist; the resolved page is the truth.
- **The inheritance boundary**: a handle's block styles the handle; a
  page's block is inherited by its descendants; nothing inherits across
  resolution. Admission test for any future property: _"if set on a
  browsable parent, do I want its resolved descendants to inherit it
  unless overridden?"_ — within a page yes, across resolution never.
- **Style is presentation-only.** No style property affects queue scope,
  playback, or navigation. Content facts with behavioral weight
  (`disabled`, `favorited`, `live`) live on `Track`; `disabled` in
  particular means _unavailable_ (never plays anywhere; grayed where the
  surface can draw it, hidden where it can't — never a normal-looking dead
  control).
- **Names are unprefixed** (the CSS vendor-prefix lesson), family-scoped
  only where a property is meaningless outside one mode (`grid*`,
  `card*`); passthrough properties keep Apple's term (`imageShape`);
  library-defined names take role words (`artworkRendering`). Degradation
  drops decorations before layout, where the layout is renderable at all.

Consequences:

- String styles, `'rail'`, `Track.childrenStyle`, and
  `artworkCarPlayTinted` are gone (nothing shipped; the wire is flat
  nested structs; no string shorthand survives). The `carPlay*` prefix
  remains only for platform feature config that isn't styling
  (`carPlaySiriListButton`, the now-playing actions).
- Nitro flattens `extends`, so the inheritance-completeness guarantee is
  a spec-level test (every inherited key read by every resolver;
  positional keys provably excluded), not a type.
- Amends ADR 0010: its `Section.style` string vocabulary is superseded by
  the block; sections themselves, queue scoping, and the flat-wire model
  are unchanged.

Alternatives — twelve shapes were tried and rejected (styles-only
vocabulary, prefixed hints with a promotion policy, flat unprefixed
fields, tagged unions in the wire, string/object mixes, boolean sprawl):
the full decision log with each shape's killing reason lives in
[`docs/section-styling-design.html`](../section-styling-design.html) §6,
which is also the complete specification. Evidence base:
[`docs/carplay-sdk-audit.html`](../carplay-sdk-audit.html).
