# Artwork

**Artwork** is the image shown for a track — the thumbnail in a browse list, the cover on the lock screen, the icon on a CarPlay row. You set one field, `artwork`, on a [`Track`](/guide/track); the library resolves and transforms it per surface and per requested size, and hands the ready-to-render result back on `artworkSource`.

This guide covers the mental model (the two configs and the output field), **which artwork renders where**, the **transform pipeline** that turns your URL into a sized request, and the platform-specific bits: SF Symbols on iOS, tinting, light/dark artwork, Android vector icons, and SVG.

## Mental model

Three names do all the work:

| Name                                   | What it is                                                                                                                     | You                                                   |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------- |
| `Track.artwork`                        | The image **you set** — an `https` URL, an `sf:` symbol (iOS), a platform URI, or a `{ light, dark }` pair.                    | set it                                                |
| `artwork` / `nowPlayingArtwork` config | How the library **fetches** that image (base URL, headers, signing, sizing).                                                   | configure once (override per route / for now-playing) |
| `Track.artworkSource`                  | The resolved, ready-to-render [`ImageSource`](/api/types/browser-nodes/#imagesource) (URL + headers) the library **produces**. | read it                                               |

`artwork` is the only field you set per track. `artworkSource` is **output-only** — the library populates it; never set it yourself.

```tsx
import { useActiveTrack } from 'react-native-audio-browser'
import { Image } from 'react-native'

function Cover() {
  const track = useActiveTrack()
  // artworkSource is the transformed result, shaped for <Image>
  return <Image source={track?.artworkSource} />
}
```

## Which artwork renders where

The library publishes art to several surfaces; each gets its image from a field/config and (where the surface knows its display size) a size hint:

| Surface                                         | Image from                                    | Size hint | Tinting                                                      |
| ----------------------------------------------- | --------------------------------------------- | --------- | ------------------------------------------------------------ |
| **Browse rows / tabs** (CarPlay / Android Auto) | `artwork` (per-route override allowed)        | no        | CarPlay: `artworkCarPlayTinted`, or a `{ light, dark }` pair |
| **In-app `<Image>`**                            | `artworkSource` (output)                      | no        | your UI                                                      |
| **iOS lock screen / Control Center**            | `nowPlayingArtwork` → falls back to `artwork` | yes       | —                                                            |
| **CarPlay Now Playing**                         | `nowPlayingArtwork` → `artwork`               | yes       | —                                                            |
| **Android notification / Android Auto**         | `nowPlayingArtwork` → `artwork`               | yes       | — (Android Auto is dark-only)                                |
| **CarPlay tile sections** (`grid` / `rail`)     | `artwork` (each tile is a Track)              | yes       | CarPlay: `artworkCarPlayTinted`, or a `{ light, dark }` pair |

**Size hint** = the surface tells the library the pixels it needs, delivered as an [`ImageContext`](/api/types/browser/#imagecontext) to your `transform` / `imageQueryParams`. Browse-time resolution has none.

Two things to take from the table:

- **One image by default.** With only `artwork` set, the same source feeds browse rows _and_ every now-playing surface. Set `nowPlayingArtwork` only when the now-playing image should differ (a larger, lock-screen-quality cover without bloating list thumbnails).
- **Size hints exist only where the surface knows its size.** Browse-time resolution has no size, so `imageQueryParams` and `context` (below) do nothing there; they kick in at load time on CarPlay / Android Auto / now-playing.
- **Tinting is for browse icons only.** `artworkCarPlayTinted` is applied when CarPlay renders browse content — list rows, tabs, and tiles — not to now-playing cover art.

## The transform pipeline

`artwork` is an [`ArtworkRequestConfig`](/api/types/browser/#artworkrequestconfig). When the library needs a track's image, it builds the request in layers, each overriding the last:

1. **`request`** — shared base config (user agent, common headers).
2. **`artwork`** — the static image config (`baseUrl`, `headers`, …).
3. **`resolve(track)` / `resolveSync(track)`** — per-track overrides built from the track's metadata.
4. **`imageQueryParams`** — appends the surface's requested size (when it reports one).
5. **`transform({ request, context })` / `transformSync`** — final tweak, with the requested size in `context`.

To build a per-track URL, use `resolve` (the track is the argument). The `{id}` token is **not** a general feature — only `nowPlayingArtwork` templates it (see below); static values in `artwork` are sent verbatim.

A typical CDN setup never needs more than `baseUrl` plus `imageQueryParams`:

```ts
import { configureBrowser } from 'react-native-audio-browser'

configureBrowser({
  // ...the rest of your config (tabs, routes, …)
  artwork: {
    baseUrl: 'https://images.example.com',
    // tell the library which query params carry the requested size
    imageQueryParams: { width: 'w', height: 'h' }
  }
})
```

With [`imageQueryParams`](/api/types/browser/#imagequeryparams) the requested size from CarPlay / Android Auto is appended automatically — `?w=200&h=200` — so each surface gets right-sized art. Omit `height` to send width only. This only fires on surfaces that report a size; browse rows (no size hint) get the URL without these params.

For anything `imageQueryParams` can't express — per-track paths, signed URLs — use `resolve` and `transform` (or their sync variants):

```ts
import { configureBrowser } from 'react-native-audio-browser'
import HmacSHA256 from 'crypto-js/hmac-sha256' // add: yarn add crypto-js

const CDN_KEY = process.env.CDN_KEY ?? '' // your CDN signing key

// sign the path locally with your key — a pure, sync function
const signUrl = (path: string) => HmacSHA256(path, CDN_KEY).toString()

configureBrowser({
  artwork: {
    baseUrl: 'https://images.example.com',
    // build the path from the track (sync — no await needed)
    resolveSync: (track) => ({ path: `/covers/${track.id}.jpg` }),
    // then sign it + size it. both are sync, so no Promise is
    // allocated per image
    transformSync: ({ request, context }) => ({
      ...request,
      query: {
        ...request.query,
        w: context?.width ? String(context.width) : '600',
        // request.path is optional — guard it before signing
        sig: signUrl(request.path ?? '')
      }
    })
  }
})
```

A `transform` (or `transformSync`) **replaces** the request — it doesn't merge — so spread `...request` (and `...request.query`) or you'll drop the `baseUrl` and other layers. The [`context`](/api/types/browser/#imagecontext) is an `ImageContext` with `width` / `height` in **pixels**, present only when the surface knows its display size (so guard with `context?.width`). `request.path` is also optional — it's only set if a `resolve` ran first — so guard it before signing (as above).

`resolveSync` / `transformSync` are no-Promise variants of `resolve` / `transform`. Reach for them when the per-track config or final tweak needs no `await` — a synced query param, a locally-computed path — to skip a Promise allocation per image (the example above is fully sync for that reason). If you set _both_ the sync and async form of the **same** stage (e.g. `transform` _and_ `transformSync`), the async one runs first and the sync one tweaks its result; `resolve` and `transform` are independent stages that both always run.

::: tip Per-route artwork
A route can override the global `artwork` with its own config — `routes: { '/premium': { artwork: { baseUrl: '…' } } }` — so different content can load images from different hosts. See the [Browser](/guide/browser) guide.
:::

## SF Symbols (iOS)

Prefix `artwork` with `sf:` to render an Apple [SF Symbol](https://developer.apple.com/sf-symbols/) instead of fetching an image — ideal for tab and category icons. Optional `bg` (background, transparent if omitted) and `fg` (symbol color, black if omitted) query params set the colors:

```ts
{
  title: 'Favorites',
  path: '/favorites',
  artwork: 'sf:heart.fill?bg=#FF0090&fg=#fff'
}
```

On CarPlay, an SF Symbol with **no explicit colors** is handed to CarPlay untinted, so CarPlay renders it in the appearance's own color (adapting to light/dark); give it a `bg`/`fg` and you get exactly those colors instead. The library renders the symbol to an image on device and caches it. This is iOS-only; on Android an `sf:` value is not a valid image.

## Tinting and platform icons

For monochrome **icons** (not full-color album art), let the system tint them to stay legible on either appearance:

- **iOS CarPlay** — set [`artworkCarPlayTinted: true`](/api/types/browser-nodes/#track) on the track. CarPlay tints it per appearance: black in light mode, white in dark.
- **Android Auto** is dark-only, so there's no per-appearance tinting — ship an appropriately colored (e.g. white) icon. An `android.resource://…/drawable/…` artwork URI is auto-detected and opted into Android Auto's **category** content style (which adds icon margins and lets Android Auto render it as an icon). The library only sets the category style; the visual treatment is Android Auto's — see [Browse display](/guide/android-auto#browse-display).

```ts
{
  title: 'Settings',
  path: '/settings',
  artwork: 'sf:gear',
  artworkCarPlayTinted: true
}
```

**SVG** is supported on both platforms and detected by a `.svg` URL suffix — serve SVGs with a path ending in `.svg` and they decode as vectors rather than raster images.

## Light and dark artwork

Tinting recolors _one_ image. When the two appearances need genuinely **different** images — a logo whose colors change, a mark with a light-on-dark counterpart — set `artwork` to an [`ArtworkVariants`](/api/types/browser-nodes/#artworkvariants) pair instead of a URL:

```ts
{
  title: 'Playlists',
  path: '/playlists',
  artwork: {
    light: 'https://images.example.com/playlists-light.png',
    dark: 'https://images.example.com/playlists-dark.png'
  }
}
```

Both fields are required — a pair with one side missing has no sensible fallback at render time.

On CarPlay browse rows and tabs the library fetches both and registers them as a single adaptive image, so switching appearance mid-drive swaps the image **in place** — no re-fetch, and no re-query of the browse tree. Each URL runs through the [transform pipeline](#the-transform-pipeline) independently, so `baseUrl`, headers, `imageQueryParams` and `resolve` / `transform` apply to both.

Everywhere a single image is required, a pair resolves to its `dark` URL:

| Surface                                  | Uses                          |
| ---------------------------------------- | ----------------------------- |
| **CarPlay browse rows / tabs**           | both, adapting per appearance |
| **Android Auto**                         | `dark` (dark-only platform)   |
| **Now-playing** (all platforms)          | `dark`                        |
| **`artworkSource`** (your own `<Image>`) | `dark`                        |

::: tip Pair or tint?
Prefer [`artworkCarPlayTinted`](#tinting-and-platform-icons) when recoloring the same shape gives the right result: it's one fetch instead of two, the variants can't drift apart, and it works from the image's alpha so the source color is irrelevant. Reach for a pair only when the appearances need different artwork.
:::

::: warning Android Auto fetches only `http(s)` artwork
Browse artwork is served to Android Auto through an internal content provider that only fetches `http`/`https` URLs. `android.resource://` and `file://` URIs pass through and render directly; other custom schemes won't load there.
:::

## Now-playing artwork

By default the now-playing image (lock screen, Control Center, CarPlay / Android Auto Now Playing) is the active track's `artwork` — the same image as its browse-row thumbnail. To resolve it differently, set `nowPlayingArtwork`, which builds the now-playing image from its **own** request and uniquely supports an `{id}` template filled from the active track's `id`:

```ts
import { configureBrowser } from 'react-native-audio-browser'

configureBrowser({
  nowPlayingArtwork: {
    baseUrl: 'https://images.example.com',
    // {id} is replaced with the active track's id
    path: '/artwork/{id}',
    // ask the CDN for a lock-screen-quality variant
    imageQueryParams: { width: 'w', height: 'h' }
  }
})
```

Now-playing surfaces request a **much larger** image than list thumbnails — on iOS, up to the screen width in pixels, capped at 1200px — so with `imageQueryParams` set you fetch a fittingly large variant rather than upscaling a tiny thumbnail.

Two separate ideas here: the **`{id}` template** is replaced with the active track's `id`; and if you _don't_ set `nowPlayingArtwork` at all, now-playing surfaces fall back to the `artwork` config (and to `track.artwork`).

`nowPlayingArtwork` is an `ArtworkRequestConfig` like `artwork`, so it takes the same `resolve` / `transform` / `imageQueryParams`. It is **native-only**: the `{id}` template and the `nowPlayingArtwork` config are not applied by the web implementation, where now-playing artwork simply uses the `artwork` config like any other surface. Artwork is also the one now-playing field the text layers can't touch — the formatter / override / flash carry only `title` / `artist` / `album`. See [Now Playing](/guide/now-playing#now-playing-artwork) for the metadata side.

::: warning Now-playing artwork follows the active track
Now-playing artwork is resolved **once per active track**, keyed on its `id` — so it won't re-resolve while the same track keeps playing. There is no imperative way to swap _only_ the image mid-stream: `updateNowPlaying()` overrides `title` / `artist` / `album`, not artwork. Changing it means making a _new_ active track (a different `id` plus the new `artwork`) the current one, which reloads playback — so it's not a fit for, say, updating live-radio cover art on each song. An in-place update API is tracked in [issue #76](https://github.com/radio-garden/react-native-audio-browser/issues/76).
:::

## Tile sections

A page's [`Section`](/api/types/browser-nodes/#section) can render its tracks as tappable artwork tiles by setting `style: 'grid'` (wrapping) or `style: 'rail'` (a single line — formerly the image row):

```ts
{
  path: '/home',
  title: 'Home',
  sections: [
    {
      title: 'Featured',
      style: 'rail',
      path: '/browse/featured', // header / "view all" target
      children: [
        { title: 'Jazz', path: '/browse/jazz', artwork: 'https://…/jazz.jpg' },
        { title: 'Rock', path: '/browse/rock', artwork: 'https://…/rock.jpg' }
      ]
    }
  ]
}
```

Each tile is an ordinary Track, so its `artwork` runs through the same pipeline as any other. Tile styles presume artwork: a track without any renders as a placeholder tile plus its title — the artwork `resolve` hook is the place to supply fallback art. On CarPlay a `rail` shows the tiles that fit (roughly eight, width-dependent; the rest are truncated) and a `grid` wraps on iOS 26+ (rendering as a list before that); Android Auto's grid always wraps, so it renders both tile styles identically. See [Browser → Presentation](/guide/browser#presentation) for the full per-surface rundown.

## API summary

| Symbol                                                          | Purpose                                                                               |
| --------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| [`Track.artwork`](/api/types/browser-nodes/#track)              | The image you set — `https` URL, `sf:` symbol (iOS), or platform URI.                 |
| [`Track.artworkSource`](/api/types/browser-nodes/#track)        | Output-only resolved `ImageSource` for your own `<Image>`.                            |
| [`Track.artworkCarPlayTinted`](/api/types/browser-nodes/#track) | Tint a monochrome icon per CarPlay light/dark (iOS only).                             |
| [`ArtworkVariants`](/api/types/browser-nodes/#artworkvariants)  | A `{ light, dark }` pair set on `artwork` when the appearances need different images. |
| [`artwork`](/api/types/browser/#artworkrequestconfig)           | Config for browse-row image requests (and the default everywhere).                    |
| `nowPlayingArtwork`                                             | Separate config for now-playing art; supports `{id}`; native-only.                    |
| [`imageQueryParams`](/api/types/browser/#imagequeryparams)      | Map the surface's requested size to your CDN's query params.                          |
| [`ImageContext`](/api/types/browser/#imagecontext)              | Requested `width`/`height` in pixels, passed to `transform`.                          |
| [`Section.style`](/api/types/browser-nodes/#sectionstyle)       | `'grid'` / `'rail'` render a section's tracks as artwork tiles.                       |
