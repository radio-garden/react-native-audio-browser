# Track

A **Track** is the one unit of content in the library. The same object describes a row in a browse list, an item in the playback queue, and the metadata on the lock screen — so once you know the Track shape, you can read and build content everywhere.

A Track is just a plain object. The only required field is `title`, plus **at least one** of `path` (browsable) or `src` (playable):

```ts
const station: Track = {
  title: 'Smooth Floret FM',
  src: 'https://example.com/floret.mp3'
}
```

A real track usually carries more — identity, metadata, and artwork:

```ts
const episode: Track = {
  id: 'atp-545',
  title: 'Episode 545',
  artist: 'Accidental Tech Podcast',
  album: 'ATP',
  artwork: 'https://example.com/atp.jpg',
  duration: 7200,
  src: 'https://example.com/atp/545.mp3'
}
```

This guide is the field-by-field reference. For _where_ tracks come from (routes, callbacks, HTTP) see the [Browser](/guide/browser) guide.

## Browsable, playable, or both

Two fields decide what a Track _does_. A Track must set at least one of them, and may set both:

| Field  | Makes the track | Effect                                               |
| ------ | --------------- | ---------------------------------------------------- |
| `path` | **browsable**   | a container — tapping it navigates into its children |
| `src`  | **playable**    | a leaf — the player can stream it                    |

```ts
// Browsable: a folder you navigate into
{ title: 'Jazz', path: '/browse/jazz' }

// Playable: a station you stream
{ title: 'Smooth Floret FM', src: 'https://example.com/floret.mp3' }

// Both: a podcast episode you can open *and* play
{ title: 'Episode 42', path: '/episodes/42', src: 'https://…/42.mp3' }
```

`src` is usually an absolute audio URL, but it can be any string — a file path or your own identifier — that you turn into a real request in `media.resolve` (see [Browser → Media and artwork](/guide/browser#media-and-artwork)).

## Identity: `id`

`id` is an **opaque, stable identifier** you control. The library never parses it — it round-trips verbatim through `setQueue`, the queue, `getActiveTrack`, and `onActiveTrackChanged`.

Why it matters: when playback moves by an _external_ control — a lock-screen / CarPlay / Android Auto / Bluetooth next-or-previous — you find out which of _your_ items became active by matching its `id`, without re-parsing the `src`:

```ts
// the callback gets a PlaybackActiveTrackChangedEvent — use event.track
const unsubscribe = onActiveTrackChanged.addListener((event) => {
  const episode = myEpisodes.find((e) => e.id === event.track?.id)
  // …update your UI for `episode`
})
```

The event also carries `index`, `lastTrack`, and `lastIndex`; `addListener` returns an unsubscribe function.

`id` is also passed to the per-track `resolve` hooks, so you can ship tracks carrying only an `id` and synthesise `src`/artwork from it at request time.

::: tip `id` is optional
If you key identity off `path`/`src`, you can skip it. One caveat: an Android Auto item picked straight from the browse tree (one you never queued) is identified only by `path`/`src`, so its `id` may be `undefined` on that path.
:::

## Display fields

These set the text shown across surfaces. Only `title` is required.

| Field                   | Shown where                                                                  |
| ----------------------- | ---------------------------------------------------------------------------- |
| `title`                 | primary line — browse rows **and** now-playing (required)                    |
| `subtitle`              | secondary line in **browse lists** only                                      |
| `artist`                | secondary line on **now-playing / lock screen**; the head-unit "artist" slot |
| `album`                 | album name (and gates the tappable album line — see `albumPath`)             |
| `genre` / `description` | extra metadata                                                               |
| `duration`              | catalog metadata in seconds, for your UI                                     |

::: warning `subtitle` and `artist` are separate — neither falls back to the other
`subtitle` drives browse-list rows; `artist` drives the now-playing line and Bluetooth metadata. Set each one for the surface you want it on.
:::

`duration` is **catalog metadata only** (e.g. an episode list) — it does _not_ drive the now-playing scrubber. Every surface derives elapsed/remaining from the player itself.

## Artwork

Set `artwork` to an image URL. It's transformed through your [artwork configuration](/guide/browser#media-and-artwork) and the ready-to-render result comes back on the **output-only** `artworkSource`, shaped for React Native's `<Image>`:

```tsx
<Image source={track.artworkSource} />
```

Don't set `artworkSource` yourself — the library populates it.

**SF Symbols (iOS).** Prefix `artwork` with `sf:` to use a system symbol, with optional colors — `bg` (background, transparent if omitted) and `fg` (symbol, black if omitted):

```ts
{
  title: 'Favorites',
  path: '/favorites',
  artwork: 'sf:heart.fill?bg=#FF0090&fg=#fff'
}
```

On CarPlay, an SF Symbol with no explicit colors adapts to light/dark mode automatically.

**Tinting (`artworkCarPlayTinted`).** For monochrome icons, set this so CarPlay tints them per appearance — black in light mode, white in dark. Use it for icons, not full-color album art. iOS CarPlay only; Android Auto is dark-only, so ship appropriately-colored (e.g. white) icons there. On Android, an `android.resource://…` artwork URI automatically gets category styling (icon margins + system tinting for vector drawables).

**Light and dark images.** When the appearances need genuinely different artwork rather than the same shape recolored, set `artwork` to an [`ArtworkVariants`](/api/types/browser-nodes/#artworkvariants) pair — `{ light, dark }`. CarPlay adapts between them in place; everywhere that needs a single image uses `dark`. See [Artwork → Light and dark](/guide/artwork#light-and-dark-artwork).

## Per-track request override

[`request`](/api/types/browser-nodes/#trackrequest) is a **narrow** per-track override for _how_ this track's audio request is made — merged last, after the shared `request` and `media` layers:

```ts
{
  title: 'Member Stream',
  src: 'https://example.com/members/floret.mp3',
  request: { headers: { authorization: 'Bearer …' } }
}
```

It carries only `userAgent`, `headers`, and `query` — deliberately **not** `baseUrl` / `path` / `method` / `body`, so a track (often server-sourced) can customise _how_ it's fetched but can't repoint its own host or verb. Like every field, it round-trips verbatim through the queue.

## Presentation on native surfaces

Optional fields that change how a Track renders on CarPlay / Android Auto. They're no-ops where not applicable — set them where they help.

| Field                             | Effect                                                                                                                                                                                              | Platform                   |
| --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------- |
| `style: 'list' \| 'grid'`         | how this item renders                                                                                                                                                                               | Android Auto / AAOS        |
| `childrenStyle: 'list' \| 'grid'` | how this container's children render                                                                                                                                                                | Android Auto / AAOS        |
| `groupTitle`                      | section header above contiguous same-group items (Android Auto / AAOS); also scopes the tap-to-play queue to the group on every surface (see [Playback behavior](/guide/browser#playback-behavior)) | all                        |
| `favorited`                       | filled/empty heart (needs the `favorite` capability)                                                                                                                                                | Android Auto, notification |
| `live`                            | a "live" indicator                                                                                                                                                                                  | iOS now-playing            |
| `imageRow`                        | render as a horizontal thumbnail strip                                                                                                                                                              | CarPlay                    |
| `albumPath`                       | make the now-playing album line tappable                                                                                                                                                            | CarPlay                    |

A couple of constraints worth knowing:

- **`childrenStyle` goes on the child as it appears in its parent's list** — Android Auto reads it there to decide how to lay out the folder once you navigate in.
- **`albumPath` requires `album`** (CarPlay renders the tappable line from album metadata), and pairs with `resolveAlbumPath` in the [Browser config](/guide/browser).
- **`imageRow` renders as thumbnails on CarPlay only** (~4–5 visible; extras are silently dropped). Android Auto has no image-row rendering, so the row expands into its items as a grid-styled group (artwork tiles where the host honors per-item content-style hints, list rows otherwise) — plus a trailing "view all" row when the track has a `path`. A track with `imageRow` but no `path` is a pure preview: on CarPlay its header isn't tappable. Tapping a playable item queues **the row's items** — the row is its own section (see [Playback behavior](/guide/browser#playback-behavior)).

```ts
{
  path: '/genres',
  title: 'Genres',
  childrenStyle: 'grid', // children laid out as tiles in Android Auto
  children: [...]
}
```

An `imageRow` is an array of [`ImageRowItem`](/api/types/browser-nodes/#imagerowitem)
(`{ title, path?, src?, artwork?, … }`) — the track's own `title` is the row header,
its `path` the header's tap-through target, and each item one tappable thumbnail. A
thumbnail with `src` **plays immediately on tap** (same selection path as a playable
list row — a station app can make thumbnails play their station directly); otherwise
its `path` is navigated. Playable items can carry the now-playing fields a regular
track would (`id`, `artist`, `album`, `albumPath`, `live`, `request`):

```ts
{
  title: 'Featured',
  path: '/browse/featured', // header tap → the full list
  imageRow: [
    { title: 'Jazz', path: '/browse/jazz', artwork: 'https://…/jazz.jpg' },
    {
      title: 'Beacon FM',
      src: 'https://audio.example.com/beacon.mp3', // tap plays it
      artist: 'Springfield',
      live: true,
      artwork: 'https://…/beacon.jpg'
    }
  ]
}
```

## ResolvedTrack — a track with children

A [**`ResolvedTrack`**](/api/types/browser-nodes/#resolvedtrack) is the same Track plus its resolved `children` — the page the browser is currently showing. Its `path` is always present, and `children` is the `Track[]` to render (optional — a leaf page has none).

Navigation is **fire-and-forget**: `navigate(path)` returns `void`. Read the resolved page from `useContent()` (or `getContent()` / `onContentChanged`):

```tsx
import { navigate, useContent } from 'react-native-audio-browser'

navigate('/browse/jazz') // moves the browser to this path

function JazzPage() {
  const page = useContent() // ResolvedTrack | undefined
  return <List data={page?.children ?? []} /> // children may be undefined
}
```

A `ResolvedTrack` may also set [`carPlaySiriListButton`](/api/types/browser/#carplaysirilistbuttonposition)`: 'top' | 'bottom'` to show the "Ask Siri to Play" cell on that page (needs Siri wiring — see [CarPlay](/guide/carplay)).

## Using tracks in your code

Tracks flow through the queue and playback API. A minimal playable track needs just `title` + `src`:

```ts
import { setQueue, play, useActiveTrack } from 'react-native-audio-browser'

setQueue([
  { title: 'Smooth Floret FM', src: 'https://example.com/floret.mp3' },
  { title: 'The Stalk 88.5', src: 'https://example.com/stalk.mp3' }
])
play()
```

`load(track)` plays a single track, `add(tracks)` appends to the queue, and `getActiveTrack()` / `useActiveTrack()` read what's playing now (with its `artworkSource` populated for your UI). See [Basic Usage](/guide/basic-usage) for the full queue and playback API.

## Where to go next

- **[Browser](/guide/browser)** — where tracks come from: tabs, routes, sources.
- **[Now Playing](/guide/now-playing)** — how a track's fields drive the lock-screen / car metadata.
- **[Favorites](/guide/favorites)** — the `favorited` field and the heart button.

For the exact types — every field and its JSDoc — see [`Track`](/api/types/browser-nodes/#track) and [`ResolvedTrack`](/api/types/browser-nodes/#resolvedtrack) in the API reference.
