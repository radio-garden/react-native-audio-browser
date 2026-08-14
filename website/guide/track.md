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

`id` is also passed to the per-track `resolve` hooks, so you can set `src` to the same stable identifier and synthesise the real request/artwork from it at request time.

::: tip `id` is optional
A track's identity is its `id`, falling back to `src` — so if the same item's `src` string is identical wherever it appears, you can skip `id` entirely. Set ids consistently across your content or not at all: identity is compared whole, and a row carrying an `id` never matches a track without one.
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

**Tinting (`style: { artworkRendering: 'stencil' }`).** For monochrome icons, declare this so CarPlay tints them per appearance — black in light mode, white in dark. Use it for icons, not full-color album art. iOS CarPlay only; Android Auto is dark-only, so ship appropriately-colored (e.g. white) icons there. On Android, an `android.resource://…` artwork URI selects the category variant of any emitted display hint (icon margins + system tinting for vector drawables).

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

Optional fields that change how a Track renders on CarPlay / Android Auto. Declarations are aspirational — inert where not applicable, never an error.

| Field                                    | Effect                                                            | Platform                   |
| ---------------------------------------- | ----------------------------------------------------------------- | -------------------------- |
| `style: { display: 'list' \| 'grid' }`   | on a browsable track: the layout _promise_ for the page it opens  | Android Auto / AAOS        |
| `style: { artworkRendering: 'stencil' }` | tint monochrome artwork to the surface appearance                 | CarPlay                    |
| `disabled`                               | unavailable: never plays; grayed where drawable, hidden elsewhere | all                        |
| `favorited`                              | filled/empty heart (needs the `favorite` capability)              | Android Auto, notification |
| `live`                                   | a "live" indicator                                                | iOS now-playing            |
| `albumPath`                              | make the now-playing album line tappable                          | CarPlay                    |

A couple of constraints worth knowing:

- **`display` goes on the browsable track as it appears in its parent's list** — Android Auto reads it there, before the page resolves, to decide how to lay out the folder once you navigate in (ADR 0011: the promise is emitted only when declared — never derived). The drilled-into page's own sections override it per section via their `style`.
- **`albumPath` requires `album`** (CarPlay renders the tappable line from album metadata), and pairs with `resolveAlbumPath` in the [Browser config](/guide/browser).

```ts
// The handle, as it appears in its PARENT's list (no children here — that's
// the page's job): the promise for the page it opens.
{
  path: '/genres',
  title: 'Genres',
  style: { display: 'grid' } // the page this opens lays out as tiles in Android Auto
}
```

## Sections — grouping and tile layouts

Headers and tile layouts aren't Track fields — they're declared on the **page**, as [`Section`](/api/types/browser-nodes/#section)s (`{ title?, subtitle?, style?, path?, children }`). A section groups tracks under a header, and its `style` block's `display` picks their layout: `'list'` rows (the default) or `'grid'` artwork tiles — wrapping unless `gridWrap: false` keeps them to a single line (the teaser shelf). The section's `path` is the navigation target for its header / "view all" surface; a section without one is a pure preview, its header not tappable.

A tile with `src` **plays immediately on tap** (same selection path as a playable list row — a station app can make tiles play their station directly); otherwise its `path` is navigated. Tiles are ordinary Tracks, so playable ones carry the now-playing fields any track would (`id`, `artist`, `album`, `albumPath`, `live`, `request`). Tapping a playable child queues **its section** on every surface (see [Playback behavior](/guide/browser#playback-behavior)):

```ts
{
  path: '/home',
  title: 'Home',
  sections: [
    {
      title: 'Featured',
      style: { display: 'grid', gridWrap: false },
      path: '/browse/featured', // header tap → the full list
      children: [
        { title: 'Jazz', path: '/browse/jazz', artwork: 'https://…/jazz.jpg' },
        {
          title: 'Beacon FM',
          src: 'https://audio.example.com/beacon.mp3', // tap plays it
          artist: 'Springfield',
          live: true,
          artwork: 'https://…/beacon.jpg'
        }
      ]
    },
    { title: 'All stations', children: [...] }
  ]
}
```

Each surface renders a declaration as its nearest supported form — CarPlay truncates a single-line grid (`gridWrap: false`) at the tiles that fit, Android Auto's grid always wraps, app UIs typically render a horizontal scroller — see [Browser → Presentation](/guide/browser#presentation) for the full rundown.

## ResolvedTrack — a resolved page

A [**`ResolvedTrack`**](/api/types/browser-nodes/#resolvedtrack) is the same Track plus its resolved content — the page the browser is currently showing. Its `path` is always present, and `sections` is the [`Section`](/api/types/browser-nodes/#section)`[]` to render (optional — a leaf page has none). A page authored with a plain `children` list resolves to a single untitled section: `children` is authoring sugar, never populated on resolved output — read `sections`.

Navigation is **fire-and-forget**: `navigate(path)` returns `void`. Read the resolved page from `useContent()` (or `getContent()` / `onContentChanged`):

```tsx
import { navigate, useContent } from 'react-native-audio-browser'

navigate('/browse/jazz') // moves the browser to this path

function JazzPage() {
  const page = useContent() // ResolvedTrack | undefined
  // each section: { title?, style?, path?, children: Track[] }
  return <List sections={page?.sections ?? []} /> // sections may be undefined
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
