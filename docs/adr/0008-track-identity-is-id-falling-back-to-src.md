# Track identity is `id`, falling back to `src`

**Status:** accepted

A Track's **identity** — what makes two Tracks the same item — is its `id` when set (non-blank), falling back to its `src`. One rule, applied at every comparison site: favorites matching, the CarPlay / Android Auto now-playing row indicator, section scoping and skip-in-place, and the contextual `__trackId` queue re-expansion. The fallback is per-Track, not per-comparison: a Track carrying an `id` compares by that id alone. With favorites keyed on identity, the `FavoritesMatchMode` type and the `FavoriteConfig` object are deleted — the `favorite` capability is a plain boolean, and `setFavorites(ids)` compares its ids exactly against track identity.

Before this decision, identity was smeared across the library. Favorites compared against `src` only, with a two-mode knob: `'exact'` (identifier equals `src`) or `'partial'` (identifier appears as a path segment _inside_ `src`). Sections, skip-in-place, and the now-playing indicator each had their own comparison rule, so the same two Tracks could count as "the same item" on one surface and not on another. The `'partial'` mode existed only because favorites couldn't key on `id` — and it forced servers to embed the stable identifier as a path segment of the playable `src`, a real production constraint: the stream URL had to stay parseable by the favorites matcher, so it couldn't be an opaque signed or CDN-rotated URL. Consumers, for their part, kept `id ?? parse-uid-from-src` fallbacks in their own code because the round-trip wasn't uniform — the id they put on a Track wasn't reliably the id that came back out of every event and comparison.

Two smaller corrections ride along. iOS previously overwrote a caller-set `track.favorited` during hydration ([#42](https://github.com/radio-garden/react-native-audio-browser/issues/42)); all platforms now let a caller-set flag win. And the docs claimed that server-supplied `favorited` populates the favorites cache as the listener browses — no platform ever implemented that. The corrected contract: a caller-set `favorited` wins on display, but the cache is written only by `setFavorites` and heart toggles.

## Considered options

- **Keep `'partial'` matching** — rejected. It treats the symptom (favorites can't key on `id`) while leaving the disease: identity stays per-feature, and the constraint that servers must embed the stable identifier inside the playable `src` stays with it.
- **An identity-extractor callback** (an earlier draft in [#43](https://github.com/radio-garden/react-native-audio-browser/issues/43)) — rejected. It pushes a parsing policy into every consumer, and a JS callback can't reach the native comparison sites cheaply — the indicator, section scoping, and skip-in-place all compare on the native side, where a per-comparison JS round-trip is not an option.
- **Per-feature identity rules** (status quo) — rejected. Each surface drifts on its own; every new comparison site re-decides what "the same item" means.
- **`id` falling back to `src`, everywhere (chosen)** — the field consumers already use for identity becomes the identity, and `src` keeps id-less integrations working unchanged.

## Consequences

- **`setFavorites` ids match exactly against identity** — store the same stable identifier you assign to `Track.id`, or the full `src` for id-less tracks. No substring matching remains.
- **Servers may make `src` opaque** — signed URLs, CDN rotation, per-session tokens — since favorites no longer parse the identifier out of it.
- **Mixed id-presence never matches.** A row with an `id` no longer src-matches a track without one. Set ids consistently — everywhere or nowhere — and this is documented as the contract, not a migration wrinkle.
- **Contextual `__trackId` carries the identity**, not the raw `src`, so resume state persisted before this change degrades gracefully, once: the track resumes without its contextual queue expansion, and the next save writes the identity.
- **[#43](https://github.com/radio-garden/react-native-audio-browser/issues/43) is obsoleted** (no extractor needed once identity is uniform) and **[#42](https://github.com/radio-garden/react-native-audio-browser/issues/42) is fixed** — a caller-set `favorited` wins on all platforms.
