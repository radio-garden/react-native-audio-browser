# CarPlay search button — design

**Date:** 2026-06-18
**Status:** Approved (brainstorm), pending implementation plan
**Scope:** iOS / CarPlay only, inside `react-native-audio-browser`

## Goal

Make search reachable from CarPlay, the way Apple Music does: a magnifying-glass
button in the top-right (trailing) navigation-bar slot of browse lists. Tapping it
opens a `CPSearchTemplate` (the only CarPlay surface that can capture typed text),
and results play or drill in like any other browse item.

The user picked the **top-right nav-bar button** entry point (over a "Search" row or
a dedicated Search tab) because it is persistent, one tap from anywhere, and least
clunky.

## Key finding: the search backend already exists

Search is already wired end-to-end in the library; this feature only adds the
CarPlay UI on top of it. Nothing in the request/parse path needs to change.

- Nitro spec already exposes `onSearch(query: string): Promise<Track[]>`
  (`src/specs/audio-browser.nitro.ts`).
- Swift `BrowserManager.search(_ query:)` (`ios/Browser/BrowserManager.swift`)
  resolves the `__search__` route, applies the configured `search` transform, issues
  the HTTP request, parses a bare `Track[]`, and caches the last query/result.
- The consuming app already configures a search source: `configuration.ts` has a
  `search.transformSync` that rewrites requests to the search endpoint and adds the
  audio-browser query params. So the search route is present and active.
- `CarPlayListItemFactory` already turns `Track` nodes into `CPListItem`s for browse
  lists, and browse lists already have a tap handler that plays (`src`) or drills
  (`url`).

What is **missing** is only the CarPlay UI: no `CPSearchTemplate` is referenced
anywhere in `ios/`, and no nav-bar buttons are set on the list templates.

Consequence: **no changes to the Nitro spec, the TS layer, Android/Kotlin, the API,
or the frontend.** This is a pure `ios/CarPlay/` change.

## Components

All in `react-native-audio-browser/ios/CarPlay/`.

### 1. Nav-bar search button

In `CarPlayController.swift`, when constructing each browse `CPListTemplate`, set its
`trailingNavigationBarButtons` to a single `CPBarButton` whose image is the system
`magnifyingglass` SF Symbol. The button's handler pushes the search template onto the
interface controller.

- Shown on **all** browse lists (tab roots and drilled-in lists), so search is
  reachable from anywhere — not just one tab.
- The system-managed "Now Playing" nav button is separate from
  `trailingNavigationBarButtons`, so there is no conflict; the search icon sits
  alongside it as in the mockup.

### 2. `CarPlaySearchManager.swift` (new)

Owns a `CPSearchTemplate` and implements `CPSearchTemplateDelegate`:

- `searchTemplate(_:updatedSearchText:completionHandler:)`
  - Ignore empty/whitespace-only queries (return no results without a request).
  - Debounce keystrokes; only the latest query's results are delivered.
  - Call the existing `browserManager.search(query)`.
  - Map the returned `Track[]` to `[CPListItem]` via the existing
    `CarPlayListItemFactory`.
  - Cap to CarPlay's maximum search-result count.
  - Deliver via the completion handler.
- `searchTemplate(_:selectedResult:completionHandler:)`
  - Route through the **same** selection path browse lists use (see component 3).

### 3. Shared selection path

Extract the current browse list-item tap handler so browse lists and search results
share one implementation:

- `Track.src` present → play the channel and show Now Playing.
- `Track.url` present → push a `CPListTemplate` for that path (drill in).

No play/drill logic is duplicated for search.

## Data flow

```
tap 🔍  → push CPSearchTemplate
type    → updatedSearchText → browserManager.search(q) → Track[]
                            → CPListItem[] (capped) → completionHandler
select  → Track → src? play + Now Playing
                : url? push CPListTemplate (drill in)
```

## Enablement

The button **auto-appears whenever a search source is configured** — i.e. when a
`__search__` route is present in the resolved config. No new config field. The
consuming app already configures search, so the button simply lights up.

(Rejected alternative: an explicit `carPlaySearch: true` flag. Unnecessary given we
own the library and no app has shipped yet; auto-show is simpler and has no downside
for consumers without a search source, who just won't see the button.)

## Error & edge handling

- Empty / whitespace-only query → no request, empty results.
- Search failure / network error → empty result list; CarPlay renders its built-in
  "No results" state. (`BrowserManager.search` already degrades to empty on Android;
  the iOS path must do the same rather than surfacing an error template.)
- Rapid typing → debounce; the existing last-query cache in `BrowserManager.search`
  avoids redundant requests; the most recent query's completion wins.
- Result count → capped to CarPlay's documented maximum.

## Testing

- Swift unit test (following existing CarPlay test patterns) covering:
  - `Track[] → CPListItem` mapping for search results.
  - Selection routing: `src` → play, `url` → drill.
- Manual pass in the CarPlay simulator: button visibility on browse lists, typing,
  result selection (both a channel and a container), empty-query and no-results
  states.

## Out of scope

- **Android Auto search UI.** The `onSearch` plumbing exists on Android too, but the
  Android Auto entry point (e.g. `MediaBrowserService.onSearch` surfacing) is a
  separate follow-up task.
- Any change to search ranking, the search endpoint, or result shape.
