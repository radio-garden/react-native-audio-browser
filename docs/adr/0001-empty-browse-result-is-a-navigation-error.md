# Empty browse result is modeled as a navigation error

**Status:** accepted

When a browse path resolves successfully but yields zero children (an empty Favorites tab, a search with no results, an emptied playlist), we surface it as a `NavigationError` with code `empty` — not as a separate empty-state concept. This lets empty and failure share the one path-aware `formatNavigationError` formatter, so a consumer can word an empty Favorites tab differently from an empty search without a second mechanism. On External surfaces both render through the same centered empty view (`CPListTemplate.emptyViewTitleVariants` on CarPlay, the equivalent on Android Auto).

## Considered options

- **A dedicated `formatEmpty({ path })` hook** — keeps empty and error as distinct concepts, but adds a parallel mechanism doing identical path-aware formatting.
- **Container-owned empty copy** — the resolved page declares its own empty text. Conceptually cleanest (the page knows what it is), but needs a new content/spec field plus backend/route cooperation.
- **A single global empty string** (e.g. a `setStrings.browserEmpty`) — too weak: empty means different things per container, so one string can't serve empty-Favorites and empty-search.

Unification won because the display is identical to an error and the existing formatter already carries `path` plus an overridable default; a second hook earned its keep only on purity grounds.

## Consequences

- **"Error" is a misnomer for the empty case** — an empty result is a *successful* resolve, not a failure (cf. the "Track" naming). The name is kept because renaming `NavigationError` / `formatNavigationError` / `FormattedNavigationError` is a breaking public-API change not worth the precision.
- **The `empty` code's default must be neutral** — `title: "Nothing here"`, no retry CTA — so a consumer who never implements `formatNavigationError` gets a clean empty state, not a "Couldn't load / try again" failure treatment on a healthy empty list.
- **`empty` is distinct from `content-not-found`**: the latter is a 404-style failure (the path didn't resolve), the former is "resolved fine, zero children".
