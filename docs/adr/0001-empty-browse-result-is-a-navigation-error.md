# Empty browse result is modeled as a navigation error

**Status:** accepted

When a browse path resolves successfully but yields zero children, we surface it as a `NavigationError` with code `empty-content`, not as a separate empty-state concept. Examples are an empty Favorites tab, a search with no results and an emptied playlist.

Empty and failure then share the one path-aware `formatNavigationError` formatter. A consumer can word an empty Favorites tab differently from an empty search without a second mechanism.

On External surfaces both render through whatever carries a message there:

- **CarPlay:** the centered empty view (`CPListTemplate.emptyViewTitleVariants`).
- **Android Auto:** its browse list has no empty view, so a single non-browsable, non-playable tile carries the same formatted title and message. It sits alongside the offline, browse-error and gate tiles.

## Considered options

- **A dedicated `formatEmpty({ path })` hook** — keeps empty and error as distinct concepts, but adds a parallel mechanism doing identical path-aware formatting.
- **Container-owned empty copy** — the resolved page declares its own empty text. Conceptually cleanest (the page knows what it is), but needs a new content/spec field plus backend/route cooperation.
- **A single global empty string** (e.g. a `setStrings.browserEmpty`) — too weak: empty means different things per container, so one string can't serve empty-Favorites and empty-search.

Unification won because the display is identical to an error and the existing formatter already carries `path` plus an overridable default; a second hook earned its keep only on purity grounds.

## Consequences

- **"Error" is a misnomer for the empty case** — an empty result is a _successful_ resolve, not a failure (cf. the "Track" naming). The name is kept because renaming `NavigationError` / `formatNavigationError` / `FormattedNavigationError` is a breaking public-API change not worth the precision.
- **The `empty-content` code's default must be neutral** — `title: "Nothing here"`, no retry CTA — so a consumer who never implements `formatNavigationError` gets a clean empty state, not a "Couldn't load / try again" failure treatment on a healthy empty list.
- **`empty-content` is distinct from `content-not-found`**: the latter is a failure (no route matched the path, or its resolution returned no content), the former is "resolved fine, zero children".
