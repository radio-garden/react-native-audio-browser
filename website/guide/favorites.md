---
description: 'Let listeners heart the active track from the Android notification, Android Auto, CarPlay, and Siri, and surface a Favorites tab and voice command.'
---

# Favorites

**Favorites** let a listener mark the active track with a heart: in the Android notification and system media controls, Android Auto, the CarPlay now-playing screen, and through Siri. They also let you surface a "Favorites" tab and a "play my favorites" voice command.

The library tracks favorite _state_ (which track is favorited, and keeping the heart in sync everywhere); **your app owns the collection** (where favorites are stored and how they persist).

## Enabling favorites

Favoriting is off until you enable the `favorite` capability in `setupPlayer` (or `updateOptions`):

```ts
import { setupPlayer } from 'react-native-audio-browser'

await setupPlayer({
  capabilities: { favorite: true }
})
```

The identifiers you pass to [`setFavorites`](#hydrating-favorite-state) are compared against each track's **identity**: its `id` when set, falling back to its `src` ([`getTrackIdentity`](/api/utils/getTrackIdentity/) computes it). Store the same stable identifier you put in `Track.id` — or, for tracks without ids, the full `src`:

```ts
import { setFavorites } from 'react-native-audio-browser'

// track = { id: 'abc123', src: 'https://stream.example.com/jazz.mp3' }
setFavorites(['abc123']) // matches by id

// id-less track = { src: 'https://stream.example.com/jazz.mp3' }
setFavorites(['https://stream.example.com/jazz.mp3']) // matches by src
```

## Hydrating favorite state

The library keeps a native cache of which tracks are favorited. It paints hearts on Android Auto browse rows and the now-playing heart without a round-trip to JS, and **without your content API having to know which tracks are favorites**.

Declare your favorites once with `setFavorites`; the library stamps `favorited` on any track whose [identity](#enabling-favorites) matches, wherever that track appears. Your content endpoints stay favorites-agnostic — they return the same tracks for everyone, and favorite state lives entirely in your app/user storage.

```ts
import { setFavorites } from 'react-native-audio-browser'

// string[] of track identities: ids, or src URLs for id-less tracks
const favorites = await loadFavoritesFromStorage()
setFavorites(favorites)
```

You only need `setFavorites` for the initial hydrate. After that, taps on the system heart and calls to [`setActiveTrackFavorited`](#favoriting-from-your-own-ui) update the cache for you.

Your API can also return `favorited: true` on a track directly. A caller-set flag always wins over hydration for display — the library never overwrites it — but it does **not** fill the favorites cache. It only affects the response it arrived in, so other appearances of the same track stay unhearted.

Treat `setFavorites` as the source of truth, and server-supplied `favorited` as a per-response display override.

## Favoriting from system controls

Where the heart shows up depends on the platform:

- **Android:** once the capability is on, the heart sits in the overflow of the notification, Android Auto and the system media controls. You need no layout for it. A [`remoteButtonLayout`](/guide/remote-controls#button-layout-android) places it, and a layout that leaves out `'favorite'` drops it.
- **iOS:** the only heart is a button on the CarPlay now-playing screen, and you opt into it. Control Center and the lock screen show none.

```ts
import { setupPlayer } from 'react-native-audio-browser'

await setupPlayer({
  capabilities: { favorite: true },
  ios: { carPlayNowPlayingButtons: ['favorite'] }
})
```

On iOS, Siri can also favorite or unfavorite the playing track ("add this to my favorites", "I like this") once Siri is wired up as in [CarPlay → Siri Voice Search](/guide/carplay#siri-voice-search), with `INAddMediaIntent` and `INUpdateMediaAffinityIntent` also listed in `INIntentsSupported`. Favorites need no `search` source.

Every favorite change, from any source, updates the library's cache and the hearts, then emits `onFavoriteChanged`: a system heart tap, a Siri request, and your own calls to [`setActiveTrackFavorited`](#favoriting-from-your-own-ui) alike. Persist the change to your storage or backend here, and only here:

```ts
import { onFavoriteChanged } from 'react-native-audio-browser'

const unsubscribe = onFavoriteChanged.addListener(({ track, favorited }) => {
  if (favorited) addToFavorites(track)
  else removeFromFavorites(track)
})
```

The native side has already updated the now-playing heart by the time this fires, so your handler persists the change, then refreshes the `/favorites` content (see [Keep it in sync](#keep-it-in-sync)).

## Favoriting from your own UI

To drive favoriting from an in-app button (rather than a system control), set or toggle the active track's state directly. These update the same cache and system hearts:

```ts
import {
  setActiveTrackFavorited,
  toggleActiveTrackFavorited
} from 'react-native-audio-browser'

setActiveTrackFavorited(true)
toggleActiveTrackFavorited()
```

Both emit `onFavoriteChanged` like a system heart tap does, so the listener above persists the change. Don't persist it again in your button handler.

## Surfacing favorites

How you surface favorites in your [browse tree](/guide/basic-usage) is up to you — a top-level tab, a nested entry, or a group within a larger list. Whatever the placement, point its route at the listener's favorited tracks:

```ts
import { configureBrowser } from 'react-native-audio-browser'

configureBrowser({
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

The library caches each path's content, so `/favorites` keeps showing its old content until you tell it to re-fetch. **Whenever the favorites collection changes**, re-fetch it.

When the change came from outside the player, such as a sync from another device or an edit on your server, also refresh the hearts:

```ts
import { setFavorites, notifyContentChanged } from 'react-native-audio-browser'

// only for changes from outside the player: refresh the hearts
setFavorites(updatedIds)
// always: re-fetch the /favorites content
notifyContentChanged('/favorites')
```

These hit two different caches. `setFavorites` updates the `favorited` flag wherever a track appears. A heart tap, a Siri request or `setActiveTrackFavorited` already updates it, so for those the call is redundant, though harmless.

[`notifyContentChanged(path)`](/api/features/browser/#notifycontentchanged) re-runs the route handler for that one path and refreshes any surface currently showing it. Use [`invalidateAllContent()`](/api/features/browser/#invalidateallcontent) instead only when _everything_ should re-fetch, such as on a locale switch or sign-out.

You can drive both calls from one place that observes your favorites list, rather than from each source of change.

## Searching within favorites

Voice intents can target the favorites collection — both "play my favorites" and a scoped search like "play my jazz". These arrive at your `search` source with `reference: 'my'`. See [Search → Playing the user's own collection](/guide/search#playing-the-users-own-collection) for how to resolve them against a local or server collection.

## API summary

| API                                                              | Purpose                                                                                                                                                         |
| ---------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `capabilities: { favorite: true }`                               | Enable favoriting and the system hearts.                                                                                                                        |
| `setFavorites(ids)`                                              | Hydrate the favorites cache; ids match each track's identity (`id`, falling back to `src`).                                                                     |
| `track.favorited`                                                | Per-track display flag; a caller-set value wins over hydration but does not fill the cache.                                                                     |
| `setActiveTrackFavorited(bool)` / `toggleActiveTrackFavorited()` | Favorite the active track from your own UI.                                                                                                                     |
| `onFavoriteChanged`                                              | Fires on every favorite change (system heart, Siri, your own calls); persist the change here.                                                                   |
| `notifyContentChanged('/favorites')`                             | Re-fetch the `/favorites` content after the collection changes.                                                                                                 |
| `'favorite'` in `android.remoteButtonLayout`                     | Places the Android heart. Without a layout it sits in the overflow already. A layout replaces the defaults wholesale, so list the other buttons you still want. |
| `ios.carPlayNowPlayingButtons: ['favorite']`                     | The heart on the CarPlay now-playing screen, the only one on iOS.                                                                                               |
| `search` source with `reference: 'my'`                           | Resolve "play my favorites" / favorites search.                                                                                                                 |
