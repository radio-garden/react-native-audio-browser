# Favorites

**Favorites** let a listener mark the active track with a heart — on the now-playing screen, the notification, CarPlay, and Android Auto — and let you surface a "Favorites" tab and a "play my favorites" voice command. The library tracks favorite *state* (which track is favorited, and keeping the heart in sync everywhere); **your app owns the collection** (where favorites are stored and how they persist).

## Enabling favorites

Favoriting is off until you enable the `favorite` capability in `setupPlayer` (or `updateOptions`):

```ts
await AudioBrowser.setupPlayer({
  capabilities: { favorite: true },
})
```

`favorite: true` is shorthand for `{ match: 'exact' }`. The `match` mode controls how the identifiers you pass to [`setFavorites`](#hydrating-favorite-state) are compared against a track's `src` to decide whether it's favorited:

- **`'exact'`** — the identifier must equal `src`. Use this when you store favorites as the full playable URL.
- **`'partial'`** — the identifier matches if it appears as a complete path segment within `src` (delimited by `/`, `?`, `#`, or the string boundaries). Use this when your favorites are a stable ID that's *embedded in* — but not equal to — the `src` URL.

```ts
// setFavorites(['abc123']) with track.src = '/stream/jazz-fm/abc123'
//   match: 'exact'    → 'abc123' !== '/stream/jazz-fm/abc123'   → not favorited
//   match: 'partial'  → 'abc123' is the last segment of src      → favorited
capabilities: { favorite: { match: 'partial' } }
```

## Hydrating favorite state

The library keeps a native cache of which tracks are favorited, so it paints hearts across browse rows and the Now Playing heart without a round-trip to JS — and **without your content API having to know which tracks are favorites**. Declare your favorites once; the library matches them against each track's `src` wherever that track appears.

**Declare them with `setFavorites` (recommended).** Pass your stored favorite ids at launch; the library stamps `favorited` on any track whose `src` matches (see [match modes](#enabling-favorites)). Your content endpoints stay favorites-agnostic — they return the same tracks for everyone, and favorite state lives entirely in your app/user storage.

```ts
const favorites = await loadFavoritesFromStorage() // string[] of ids or src URLs
AudioBrowser.setFavorites(favorites)
```

**Or embed `favorited` in your responses** — if your API already personalizes per user, return the flag directly and the cache fills as the listener browses:

```jsonc
{ "title": "Smooth Jazz FM", "src": "https://stream.example.com/jazz.mp3", "favorited": true }
```

You only need `setFavorites` for the initial hydrate. After that, taps on the system heart and calls to [`setActiveTrackFavorited`](#favoriting-from-your-own-ui) update the cache for you.

## Favoriting from system controls

With the capability enabled, a heart appears on the now-playing surfaces. Add it to the Android notification and the CarPlay now-playing screen explicitly:

```ts
await AudioBrowser.setupPlayer({
  capabilities: { favorite: true },
  android: { notificationButtons: { overflow: ['favorite'] } },
  ios: { carPlayNowPlayingButtons: ['favorite'] },
})
```

When the listener taps a system heart, the library updates its cache and the UI, then emits `onFavoriteChanged` so **you can persist the change** to your storage or backend:

```ts
const unsubscribe = AudioBrowser.onFavoriteChanged.addListener(
  ({ track, favorited }) => {
    if (favorited) addToFavorites(track)
    else removeFromFavorites(track)
  },
)
```

The native side has already updated the now-playing heart by the time this fires, so your handler just persists the change — then refreshes the rest (see [Keep it in sync](#keep-it-in-sync)).

## Favoriting from your own UI

To drive favoriting from an in-app button (rather than a system control), set or toggle the active track's state directly. These update the same cache and system hearts:

```ts
AudioBrowser.setActiveTrackFavorited(true)
AudioBrowser.toggleActiveTrackFavorited()
```

These are the programmatic counterpart to a system heart tap — persist the result yourself, the same way you would in `onFavoriteChanged`.

## Surfacing favorites

How you surface favorites in your [browse tree](/guide/basic-usage) is up to you — a top-level tab, a nested entry, or a group within a larger list. Whatever the placement, point its route at the listener's favorited tracks:

```ts
AudioBrowser.configureBrowser({
  tabs: [
    { title: 'Browse', url: '/browse' },
    { title: 'Favorites', url: '/favorites' },
  ],
  routes: {
    '/favorites': async () => ({
      url: '/favorites',
      title: 'Favorites',
      children: await loadFavoriteTracks(), // your stored favorites, as a Track[]
    }),
  },
})
```

Resolve it however your collection lives — from local storage, or from your API (with an HTTP route that posts your stored identifiers). It renders natively on CarPlay and Android Auto like any other browse content.

### Keep it in sync

The library caches each browse route, so the `/favorites` route keeps showing its old contents until you tell it to re-fetch. **Whenever the favorites collection changes** — a system heart tap, your own UI, or a sync from another device — do two things:

```ts
AudioBrowser.setFavorites(updatedIds)        // refresh the hearts (favorited flag)
AudioBrowser.notifyContentChanged('/favorites') // re-fetch the /favorites content
```

These hit two different caches: `setFavorites` updates the `favorited` flag wherever a track appears, while [`notifyContentChanged(path)`](/api/) re-runs the route handler for that one path and refreshes any surface currently showing it. (Use `invalidateAllContent()` instead only when *everything* should re-fetch — e.g. a locale switch or sign-out.) Driving both from a single place that observes your favorites list — rather than from each individual toggle — keeps every source of change covered.

## Searching within favorites

Voice intents can target the favorites collection — both "play my favorites" and a scoped search like "play my jazz". These arrive at your `search` source with `reference: 'my'`. See [Search → Playing the user's own collection](/guide/search#playing-the-users-own-collection) for how to resolve them against a local or server collection.

## API summary

| API | Purpose |
| --- | --- |
| `capabilities: { favorite: true \| { match } }` | Enable favoriting and choose how ids match `src`. |
| `setFavorites(ids)` | Hydrate the favorites cache on launch. |
| `track.favorited` | Per-track flag; auto-populates the cache during browse. |
| `setActiveTrackFavorited(bool)` / `toggleActiveTrackFavorited()` | Favorite the active track from your own UI. |
| `onFavoriteChanged` | Subscribe to system heart taps to persist the change. |
| `notifyContentChanged('/favorites')` | Re-fetch the `/favorites` content after the collection changes. |
| `android.notificationButtons.overflow: ['favorite']` | Heart in the Android notification. |
| `ios.carPlayNowPlayingButtons: ['favorite']` | Heart on the CarPlay now-playing screen. |
| `search` source with `reference: 'my'` | Resolve "play my favorites" / favorites search. |
