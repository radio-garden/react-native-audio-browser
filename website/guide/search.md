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
    // db / toTrack are yours
    const rows = await db.findStations(params.query)
    return rows.map(toTrack)
  }
})
```

**An HTTP endpoint** — a `TransformableRequestConfig`. The library issues the request natively and appends the query parameters for you. The endpoint must return a **page object** — `{ title?, children: Track[] }` — the same shape a browse endpoint returns. (iOS and Android require the page object; the web implementation additionally accepts a bare `Track[]` for back-compat, but return the page object so all three platforms work.)

```ts
configureBrowser({
  search: {
    baseUrl: 'https://api.example.com/search',
    // "play jazz" → GET .../search?q=jazz&genre=jazz&limit=20
    // (q always; genre because the intent carried one; no mode here
    //  because "play jazz" carries none; limit added by transform below)
    transform(request) {
      return { ...request, query: { ...request.query, limit: '20' } }
    }
  }
})
```

With the HTTP form the library appends to `request.query`: `q` (always, even when empty), then `mode`, `reference` (only when it is `'my'` — see below), and the filter fields, each included only when the intent carried it. Query-param order doesn't matter to a server, but that is the order the library writes them in. Note the spoken text is the `query` field in a **callback** (`SearchParams.query`) but is sent as the **`q`** wire param on the **HTTP** form — same value, named `query` in JS and `q` on the URL.

## Searching from your own UI

The same source powers your in-app search box. [`search(query)`](/api/features/browser/#search) runs it and resolves to a `Track[]` — it only _returns_ results, it never queues or plays anything. [`hasSearch()`](/api/features/browser/#hassearch) tells you (synchronously) whether a search source is configured at all, so you can hide the search UI when it isn't:

```tsx
import { search, hasSearch, navigate } from 'react-native-audio-browser'
import type { Track } from 'react-native-audio-browser'

function SearchScreen() {
  const [results, setResults] = useState<Track[]>([])
  if (!hasSearch()) return null // no search source configured

  return (
    <>
      <SearchInput onSubmit={async (text) => setResults(await search(text))} />
      <List
        sections={[{ children: results }]}
        onSelect={(track) => navigate(track)} // load & play via the library
      />
    </>
  )
}
```

Three things to know about the in-app call:

- **Your resolver sees a plain query.** An in-app `search('jazz')` arrives as `{ query: 'jazz', reference: 'unknown' }` — no `mode`, no filter fields. Those are extracted by the voice assistants; a text box has nothing to extract. (On the HTTP form, only `q` is appended.) A resolver written against _the fields that are present_ handles both callers with the same code.
- **You get the flat list, untouched.** The [browsable-first-result drill-in](#voice-playback-siri-google-assistant) is voice-only — in-app results are handed to you as returned (minus any result lacking both a `path` and a `src`, which is dropped as unrenderable), and the user picks.
- **Route taps through `navigate(track)`** rather than `setQueue` + `play` — that gives a tapped result the library's load path ([`handleTrackLoad`](#mixed-audio-video), [Gate](/guide/gate) checks, favorite hearts) just like a tap on a browse row.

## SearchParams

Both forms receive the same structured `SearchParams`:

| Field       | Type                | Meaning                                                                                |
| ----------- | ------------------- | -------------------------------------------------------------------------------------- |
| `query`     | `string`            | The raw query (always present, may be `""`).                                           |
| `mode`      | `SearchMode?`       | The **container vertical** — what _kind_ of result. Absent for an unstructured search. |
| `genre`     | `string?`           | Genre **filter**.                                                                      |
| `artist`    | `string?`           | Artist **filter**.                                                                     |
| `album`     | `string?`           | Album **filter**.                                                                      |
| `title`     | `string?`           | Track-title filter (song intents).                                                     |
| `playlist`  | `string?`           | Playlist-name filter.                                                                  |
| `reference` | `'my' \| 'unknown'` | Whether the user asked for _their own_ collection.                                     |

`query` and `reference` are **always present** (`reference` defaults to `'unknown'`); the short literals in the tables below omit them for brevity, but a real `SearchParams` always carries both.

## Search modes are _verticals_, not filters

`mode` answers **"what kind of thing did the user ask for?"** — a station, a podcast, a song. It is orthogonal to the filter fields, which say _which_ item:

```ts
// "play a jazz podcast"
{ mode: 'podcast', genre: 'jazz', query: 'jazz' }
//   ^ vertical      ^ filter
```

The values:

| Mode                                                    | Asked for                                                           |
| ------------------------------------------------------- | ------------------------------------------------------------------- |
| `any`                                                   | Anything sensible — smart shuffle / "play something" (empty query). |
| `station`                                               | A live radio station / channel.                                     |
| `podcast`                                               | A podcast (show, episode, or station).                              |
| `audiobook`                                             | An audiobook.                                                       |
| `news`                                                  | News content.                                                       |
| `music`                                                 | The music vertical (as opposed to talk/podcasts).                   |
| `song`                                                  | An individual track.                                                |
| `playlist`                                              | A named playlist / mix.                                             |
| `music-video` / `movie` / `tv-show` / `tv-show-episode` | Video kinds (see [Mixed audio/video](#mixed-audio-video)).          |

::: tip There is no `genre`/`artist`/`album` mode
Those are **filters**, not result shapes — read them from `params.genre` / `params.artist` / `params.album` directly. A genre search arrives as `{ genre: 'jazz' }` with `mode` left unset.
:::

`mode` is **advisory** — use it to route to the right index, or ignore it and full-text search `query`.

## Cross-platform differences

The two voice platforms feed the **same** `SearchParams`; they just differ in how many fields they fill:

- **iOS (SiriKit)** supplies the richer set — all the `mode` verticals and the `reference` axis.
- **Android (`onPlayFromSearch`)** supplies the subset it can express: `query`, the music focuses (`song` / `playlist` and the genre/artist/album filters), and always `reference: 'unknown'`.

Write your resolver against the _fields that are present_ (`if (params.genre) …`), not against the platform. A field that's empty on one platform simply means "the assistant didn't provide it."

## Voice playback (Siri & Google Assistant)

A spoken command — "play jazz on «App»" — funnels to the **same `search` source** on both platforms, then the result is queued and played. Any active **[Gate](/guide/gate)** sees the search first, so voice can't slip past a paywall or region block unless your gate lets it through. You configure `search` once; both assistants use it.

::: tip A browsable first result is drilled into
For voice playback specifically, the library inspects the **first** result. If it is a browsable-only container — a `path` (a place/genre page) with no `src` of its own — the library resolves that page and queues _its_ playable children, so "play jazz" plays the first station _inside_ the jazz page rather than the page itself. If the first result is already playable (has a `src`), or the drill-in finds nothing playable, the flat list of results is queued as-is. This applies on both platforms, and only to voice playback — an in-app search UI renders your results and lets the user pick.
:::

For ordinary queries ("play jazz", "play «station name»") the two behave identically — same resolver, same queue, same playback. The entry point and a few conveniences differ:

|                         | iOS (Siri)                                            | Android (Google Assistant)                                                                               |
| ----------------------- | ----------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| Delivered as            | `INPlayMediaIntent`                                   | `MEDIA_PLAY_FROM_SEARCH` intent                                                                          |
| "play my favorites"     | `reference: 'my'` → resolves to the user's collection | no collection signal → searched as plain text; reach favorites by **browsing** the Favorites tab instead |
| "play «App»"            | recognised as a resume                                | searched literally (no app-name heuristic)                                                               |
| bare "play" / "resume"  | resumes the current/last track                        | separate resume path (`onPlay`), not a search                                                            |
| "play music" (no query) | may send `mode: 'music'`                              | `mode: 'any'` → return smart-shuffle / recent content                                                    |

The gaps are all iOS-only conveniences — collection-by-voice and resume-by-name. Everything in the shared column is fully cross-platform.

## Playing the user's own collection

When the user says **"play my favorites"**, the intent carries `reference: 'my'`. Resolve it however your collection lives:

**Local collection** — read `params.reference` in a callback and return your own tracks:

```ts
configureBrowser({
  search: async (params) => {
    // getFavorites returns your local Track[]
    if (params.reference === 'my') return getFavorites(params)
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
          ids: getLocalFavoriteIds() // your stored identifiers
        })
      }
    }
  }
})
```

`reference` is only ever `'my'` or `'unknown'` — "currently playing" ("play this") is resolved natively as a resume and never reaches search.

## Voice phrase → params

These mappings are **illustrative, not guaranteed.** The assistant (Siri / Google Assistant) decides how to parse a spoken phrase, and the same words can arrive structured differently — or as a bare `query` — depending on the platform, locale, and the assistant's own interpretation. Treat the table as _plausible_ shapes to handle, and always write your resolver against the fields actually present (see [Cross-platform differences](#cross-platform-differences)) rather than assuming a phrase produces a specific shape.

| Phrase                | Resulting `SearchParams`                                      |
| --------------------- | ------------------------------------------------------------- |
| "play something"      | `{ query: 'something' }`                                      |
| "play music"          | `{ mode: 'any', query: '' }` _(iOS may send `mode: 'music'`)_ |
| "play jazz"           | `{ genre: 'jazz', query: 'jazz' }`                            |
| "play The Stalk 88.5" | `{ mode: 'station', query: 'The Stalk 88.5' }`                |
| "play a jazz podcast" | `{ mode: 'podcast', genre: 'jazz' }`                          |
| "play my favorites"   | `{ reference: 'my', query: '' }`                              |
| "play my jazz"        | `{ reference: 'my', genre: 'jazz' }`                          |

## Mixed audio/video

This is an **audio** library — its player streams audio and has no video surface, and CarPlay / Android Auto forbid video playback while driving. So video is fundamentally an **in-app** concern: a search can _signal_ a video request (via the video `mode` values), but the library will not render video.

To play video from your in-app search, intercept the load with [`handleTrackLoad`](/api/types/browser/#handletrackload). It runs whenever a track is loaded through [`navigate(track)`](/api/features/browser/#navigate) (or the library's own browse UI) — so route your in-app search-result taps through `navigate(track)` rather than calling `setQueue` / `play` yourself, or this hook never fires. When set, it is called **instead of** the library auto-playing the track — for **every** such load, not just video — so your handler must either route the track elsewhere or hand it back to the library to play:

```ts
import {
  configureBrowser,
  setQueue,
  play,
  type Track
} from 'react-native-audio-browser'

// You build the search-result tracks, so encode "this is video" however
// you like. `src` can be any string you recognise later — here, a scheme
// prefix your own player understands.
const isVideo = (track: Track) => track.src?.startsWith('video:') ?? false

configureBrowser({
  handleTrackLoad: async ({ track, queue, startIndex }) => {
    if (isVideo(track)) {
      openVideoPlayer(track) // your own video surface
      return
    }
    setQueue(queue, startIndex) // hand audio back to the library
    play() // setQueue does not start playback, so play() is required
  }
})
```

`setQueue` and `play` are top-level named exports, like `configureBrowser` — not methods on a browser object. `setQueue` only loads the queue; it never changes play/pause state, which is why the audio branch calls `play()` after it.

The `mode` you saw at **search** time (`music-video` / `movie` / …) is not carried onto the `Track`, and `handleTrackLoad` runs at **load** time with no `mode`. The tracks your `search` source returns for a video request are _yours_, though — so tag them with a recognizable `src` (as above) when you build them, and read that tag in `isVideo` here. On external surfaces (CarPlay / Android Auto), a video request falls back to audio or is declined — there is no video playback path there.
