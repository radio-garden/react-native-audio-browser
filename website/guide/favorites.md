# Favorites

**Favorites** let a listener mark the active track with a heart — on the now-playing screen, the notification, CarPlay, and Android Auto — and let you surface a "Favorites" tab and a "play my favorites" voice command. The library tracks favorite _state_ (which track is favorited, and keeping the heart in sync everywhere); **your app owns the collection** (where favorites are stored and how they persist).

## Enabling favorites

Favoriting is off until you enable the `favorite` capability in `setupPlayer` (or `updateOptions`):

```ts
await AudioBrowser.setupPlayer({
  capabilities: { favorite: true }
})
```

The identifiers you pass to [`setFavorites`](#hydrating-favorite-state) are compared against each track's **identity**: its `id` when set, falling back to its `src`. Store the same stable identifier you put in `Track.id` — or, for tracks without ids, the full `src`:

```ts
// track = { id: 'abc123', src: 'https://stream.example.com/jazz.mp3' }
AudioBrowser.setFavorites(['abc123']) // matches by id

// id-less track = { src: 'https://stream.example.com/jazz.mp3' }
AudioBrowser.setFavorites(['https://stream.example.com/jazz.mp3']) // matches by src
```

## Hydrating favorite state

The library keeps a native cache of which tracks are favorited, so it paints hearts across browse rows and the Now Playing heart without a round-trip to JS — and **without your content API having to know which tracks are favorites**. Declare your favorites once with `setFavorites`; the library stamps `favorited` on any track whose [identity](#enabling-favorites) matches, wherever that track appears. Your content endpoints stay favorites-agnostic — they return the same tracks for everyone, and favorite state lives entirely in your app/user storage.

```ts
// string[] of track identities: ids, or src URLs for id-less tracks
const favorites = await loadFavoritesFromStorage()
AudioBrowser.setFavorites(favorites)
```

You only need `setFavorites` for the initial hydrate. After that, taps on the system heart and calls to [`setActiveTrackFavorited`](#favoriting-from-your-own-ui) update the cache for you.

Your API can also return `favorited: true` on a track directly. A caller-set flag always wins over hydration for display — the library never overwrites it — but it does **not** fill the favorites cache: it only affects the response it arrived in, so other appearances of the same track stay unhearted. Treat `setFavorites` as the source of truth, and server-supplied `favorited` as a per-response display override.

## Favoriting from system controls

With the capability enabled, a heart appears on the now-playing surfaces. Add it to the Android notification and the CarPlay now-playing screen explicitly:

```ts
await AudioBrowser.setupPlayer({
  capabilities: { favorite: true },
  android: {
    remoteButtonLayout: {
      back: 'skip-to-previous',
      forward: 'skip-to-next',
      overflow: ['favorite']
    }
  },
  ios: { carPlayNowPlayingButtons: ['favorite'] }
})
```

When the listener taps a system heart, the library updates its cache and the UI, then emits `onFavoriteChanged` so **you can persist the change** to your storage or backend:

```ts
const unsubscribe = AudioBrowser.onFavoriteChanged.addListener(
  ({ track, favorited }) => {
    if (favorited) addToFavorites(track)
    else removeFromFavorites(track)
  }
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
    { title: 'Browse', path: '/browse' },
    { title: 'Favorites', path: '/favorites' }
  ],
  routes: {
    '/favorites': async () => ({
      path: '/favorites',
      title: 'Favorites',
      // your stored favorites, as a Track[]
      children: await loadFavoriteTracks()
    })
  }
})
```

Resolve it however your collection lives — from local storage, or from your API (with an HTTP route that posts your stored identifiers). It renders natively on CarPlay and Android Auto like any other browse content.

### Keep it in sync

The library caches each browse route, so the `/favorites` route keeps showing its old contents until you tell it to re-fetch. **Whenever the favorites collection changes** — a system heart tap, your own UI, or a sync from another device — do two things:

```ts
// refresh the hearts (favorited flag)
AudioBrowser.setFavorites(updatedIds)
// re-fetch the /favorites content
AudioBrowser.notifyContentChanged('/favorites')
```

These hit two different caches: `setFavorites` updates the `favorited` flag wherever a track appears, while [`notifyContentChanged(path)`](/api/) re-runs the route handler for that one path and refreshes any surface currently showing it. (Use `invalidateAllContent()` instead only when _everything_ should re-fetch — e.g. a locale switch or sign-out.) Driving both from a single place that observes your favorites list — rather than from each individual toggle — keeps every source of change covered.

## Searching within favorites

Voice intents can target the favorites collection — both "play my favorites" and a scoped search like "play my jazz". These arrive at your `search` source with `reference: 'my'`. See [Search → Playing the user's own collection](/guide/search#playing-the-users-own-collection) for how to resolve them against a local or server collection.

## API summary

| API                                                              | Purpose                                                                                                                                                             |
| ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `capabilities: { favorite: true }`                               | Enable favoriting and the system hearts.                                                                                                                            |
| `setFavorites(ids)`                                              | Hydrate the favorites cache; ids match each track's identity (`id`, falling back to `src`).                                                                         |
| `track.favorited`                                                | Per-track display flag; a caller-set value wins over hydration but does not fill the cache.                                                                         |
| `setActiveTrackFavorited(bool)` / `toggleActiveTrackFavorited()` | Favorite the active track from your own UI.                                                                                                                         |
| `onFavoriteChanged`                                              | Subscribe to system heart taps to persist the change.                                                                                                               |
| `notifyContentChanged('/favorites')`                             | Re-fetch the `/favorites` content after the collection changes.                                                                                                     |
| `'favorite'` in `android.remoteButtonLayout`                     | Heart in the Android notification, Android Auto, and the system media controls. A layout replaces the defaults wholesale, so list the other buttons you still want. |
| `ios.carPlayNowPlayingButtons: ['favorite']`                     | Heart on the CarPlay now-playing screen.                                                                                                                            |
| `search` source with `reference: 'my'`                           | Resolve "play my favorites" / favorites search.                                                                                                                     |
