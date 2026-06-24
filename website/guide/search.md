# Search

**Search** is the query subsystem: it turns a voice command ("play jazz") or a text query into a list of playable tracks. It is distinct from **Browse** (navigating the content tree) — search takes structured query parameters and returns results.

Search powers both your in-app search UI and the voice search on external surfaces (Siri / CarPlay, Android Auto).

## Configuring search

Set `search` on your `BrowserConfiguration`. It takes one of two shapes:

**A callback** — resolve results yourself and return a `Track[]`. The simplest possible search just filters a list you already have in memory:

```ts
import { configureBrowser } from 'react-native-audio-browser'

configureBrowser({
  // …the rest of your config (tabs, browse, request, …)
  search: async ({ query }) => {
    const q = query.toLowerCase()
    return allTracks.filter((track) => track.title?.toLowerCase().includes(q))
  }
})
```

The callback can return `Track[]` from anywhere — an in-memory filter as above, a local database, or your own API:

```ts
configureBrowser({
  search: async (params) => {
    const rows = await db.findStations(params.query)
    return rows.map(toTrack)
  }
})
```

**An HTTP endpoint** — a `TransformableRequestConfig`. The library issues the request natively and appends the query parameters for you. The endpoint must return a **page object** — `{ title?, children: Track[] }` — the same shape a browse endpoint returns:

```ts
configureBrowser({
  search: {
    baseUrl: 'https://api.example.com/search',
    // GET https://api.example.com/search?q=jazz&mode=station&genre=jazz
    transform(request) {
      return { ...request, query: { ...request.query, limit: '20' } }
    }
  }
})
```

With the HTTP form the library automatically appends `q`, `mode`, the filter fields, and `reference` (see below) to `request.query`.

## SearchParams

Both forms receive the same structured `SearchParams`:

| Field | Type | Meaning |
| --- | --- | --- |
| `query` | `string` | The raw query (always present, may be `""`). |
| `mode` | `SearchMode?` | The **container vertical** — what *kind* of result. Absent for an unstructured search. |
| `genre` | `string?` | Genre **filter**. |
| `artist` | `string?` | Artist **filter**. |
| `album` | `string?` | Album **filter**. |
| `title` | `string?` | Track-title filter (song intents). |
| `playlist` | `string?` | Playlist-name filter. |
| `reference` | `'my' \| 'unknown'` | Whether the user asked for *their own* collection. |

## Search modes are *verticals*, not filters

`mode` answers **"what kind of thing did the user ask for?"** — a station, a podcast, a song. It is orthogonal to the filter fields, which say *which* item:

```ts
// "play a jazz podcast"
{ mode: 'podcast', genre: 'jazz', query: 'jazz' }
//   ^ vertical      ^ filter
```

The values:

| Mode | Asked for |
| --- | --- |
| `any` | Anything sensible — smart shuffle / "play something" (empty query). |
| `station` | A live radio station / channel. |
| `podcast` | A podcast (show, episode, or station). |
| `audiobook` | An audiobook. |
| `news` | News content. |
| `music` | The music vertical (as opposed to talk/podcasts). |
| `song` | An individual track. |
| `playlist` | A named playlist / mix. |
| `music-video` / `movie` / `tv-show` / `tv-show-episode` | Video kinds (see [Mixed audio/video](#mixed-audio-video)). |

::: tip There is no `genre`/`artist`/`album` mode
Those are **filters**, not result shapes — read them from `params.genre` / `params.artist` / `params.album` directly. A genre search arrives as `{ genre: 'jazz' }` with `mode` left unset.
:::

`mode` is **advisory** — use it to route to the right index, or ignore it and full-text search `query`.

## Cross-platform differences

The two voice platforms feed the **same** `SearchParams`; they just differ in how many fields they fill:

- **iOS (SiriKit)** supplies the richer set — all the `mode` verticals and the `reference` axis.
- **Android (`onPlayFromSearch`)** supplies the subset it can express: `query`, the music focuses (`song` / `playlist` and the genre/artist/album filters), and always `reference: 'unknown'`.

Write your resolver against the *fields that are present* (`if (params.genre) …`), not against the platform. A field that's empty on one platform simply means "the assistant didn't provide it."

## Voice playback (Siri & Google Assistant)

A spoken command — "play jazz on «App»" — funnels to the **same `search` source** on both platforms, then the result is queued and played. Any active **[Gate](/guide/gate)** sees the search first, so voice can't slip past a paywall or region block unless your gate lets it through. You configure `search` once; both assistants use it.

For ordinary queries ("play jazz", "play «station name»") the two behave identically — same resolver, same queue, same playback. The entry point and a few conveniences differ:

| | iOS (Siri) | Android (Google Assistant) |
| --- | --- | --- |
| Delivered as | `INPlayMediaIntent` | `MEDIA_PLAY_FROM_SEARCH` intent |
| "play my favorites" | `reference: 'my'` → resolves to the user's collection | no collection signal → searched as plain text; reach favorites by **browsing** the Favorites tab instead |
| "play «App»" | recognised as a resume | searched literally (no app-name heuristic) |
| bare "play" / "resume" | resumes the current/last track | separate resume path (`onPlay`), not a search |
| "play music" (no query) | may send `mode: 'music'` | `mode: 'any'` → return smart-shuffle / recent content |

The gaps are all iOS-only conveniences — collection-by-voice and resume-by-name. Everything in the shared column is fully cross-platform.

## Playing the user's own collection

When the user says **"play my favorites"**, the intent carries `reference: 'my'`. Resolve it however your collection lives:

**Local collection** — read `params.reference` in a callback and return your own tracks:

```ts
configureBrowser({
  search: async (params) => {
    if (params.reference === 'my') return getFavorites(params)   // local Track[]
    return searchByQuery(params)
  }
})
```

**Server collection** — when you use the HTTP form, `reference=my` is appended to `request.query`, so branch in `transform` to route the request — for example, to a favorites endpoint, injecting locally-stored identifiers:

```ts
configureBrowser({
  search: {
    baseUrl: 'https://api.example.com/search',
    transform(request) {
      // `transform` runs for every search — pass normal queries straight
      // through to /search; only "play my favorites" is rewritten below.
      if (request.query?.reference !== 'my') return request
      return {
        ...request,
        method: 'POST',
        path: '/search/favorites',
        contentType: 'application/json',
        body: JSON.stringify({
          q: request.query.q,
          ids: getLocalFavoriteIds()   // your stored identifiers
        })
      }
    }
  }
})
```

`reference` is only ever `'my'` or `'unknown'` — "currently playing" ("play this") is resolved natively as a resume and never reaches search.

## Voice phrase → params

| Phrase | Resulting `SearchParams` |
| --- | --- |
| "play something" | `{ query: 'something' }` |
| "play music" | `{ mode: 'any', query: '' }` *(iOS may send `mode: 'music'`)* |
| "play jazz" | `{ genre: 'jazz', query: 'jazz' }` |
| "play The Stalk 88.5" | `{ mode: 'station', query: 'The Stalk 88.5' }` |
| "play a jazz podcast" | `{ mode: 'podcast', genre: 'jazz' }` |
| "play my favorites" | `{ reference: 'my', query: '' }` |
| "play my jazz" | `{ reference: 'my', genre: 'jazz' }` |

## Mixed audio/video

This is an **audio** library — its player streams audio and has no video surface, and CarPlay / Android Auto forbid video playback while driving. So video is fundamentally an **in-app** concern: a search can *signal* a video request (via the video `mode` values), but the library will not render video.

To play video from your in-app search, intercept the load with [`handleTrackLoad`](/api/). When set, it is called **instead of** the library auto-playing the track — so your handler can route video to your own player and let the library handle audio:

```ts
configureBrowser({
  handleTrackLoad: async ({ track, queue, startIndex }) => {
    if (isVideo(track)) {
      openVideoPlayer(track)        // your own video surface
      return
    }
    setQueue(queue, startIndex)    // hand audio back to the library
    play()
  }
})
```

The library models media-kind on neither `Track` nor the player, so carry your own "is video" marker on the track (or infer it from `src`) and read it here. On external surfaces (CarPlay / Android Auto), a video request falls back to audio or is declined — there is no video playback path there.
