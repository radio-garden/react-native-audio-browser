import type { CarPlaySiriListButtonPosition, HttpMethod } from './browser'

export type TrackStyle = 'list' | 'grid'

/**
 * Image source for React Native's `<Image>` component.
 * Contains all information needed to load an image, including URL transformation
 * and authentication headers.
 *
 * @example
 * ```tsx
 * <Image source={track.artworkSource} />
 * ```
 */
export interface ImageSource {
  /** Transformed URL with query parameters for authentication */
  uri: string
  /** HTTP method (usually GET for images) */
  method?: HttpMethod
  /** HTTP headers including User-Agent and Content-Type if configured */
  headers?: Record<string, string>
  /** HTTP body for POST requests (rare for images) */
  body?: string
}

/**
 * Per-track override applied as the most-specific layer of THIS track's media
 * request (after the shared `request` and `media` layers). Deliberately narrow:
 * a track may customize HOW its request is made (identity, auth, signed-URL
 * params), not WHERE it goes — `baseUrl`/`path`/`method`/`body` are intentionally
 * absent so a (often server-sourced) track can't repoint its own host or verb.
 * Carried verbatim on the Track and round-tripped like any other field.
 */
export interface TrackRequest {
  userAgent?: string
  headers?: Record<string, string>
  query?: Record<string, string>
}

// export interface BrowserSection {
//   title: string
//   style?: BrowserItemStyle
//   children: Track
// }

/**
 * Artwork URLs for a track that ships a different image per appearance.
 *
 * Both are required: a pair with one side missing has no sensible fallback at
 * render time, and making that unrepresentable is the reason this is a pair
 * rather than two optional fields.
 *
 * Android Auto is dark-only and takes `dark`.
 */
export interface ArtworkVariants {
  /** Shown in light mode. */
  light: string
  /** Shown in dark mode, and on Android Auto. */
  dark: string
}

// Aliased rather than written inline on `Track.artwork` because Nitro names
// variant types after their members: without the alias the generated Swift and
// Kotlin type is `Variant_String_ArtworkVariants` either way, but the alias is
// what keeps that name readable rather than accidental. Kept out of the doc
// comment below — it is a note for this repo, not for the API reference.
/** A single artwork URL, or one per appearance. */
export type TrackArtwork = string | ArtworkVariants

export interface ImageRowItem {
  /**
   * Opaque, stable identifier for this item's track. Round-tripped like
   * `Track.id` when the thumbnail is played (see `src`).
   */
  id?: string
  /** Navigation path. Tapping this thumbnail navigates to this path. */
  path?: string
  /**
   * Direct audio source. When present, tapping this thumbnail plays it
   * immediately (same selection path as a playable list row) instead of
   * navigating. Takes precedence over `path`.
   */
  src?: string
  /** Artwork URL for the thumbnail image. */
  artwork?: string
  /** Output only — populated by the artwork transform pipeline. */
  readonly artworkSource?: ImageSource
  /** Title of this item. Shown as the thumbnail caption; used for identification and accessibility. */
  title: string
  /** Now-playing secondary line when played via `src` (mirrors `Track.artist`). */
  artist?: string
  /** Now-playing album line when played via `src` (mirrors `Track.album`). */
  album?: string
  /** Navigation target of the album line when played via `src` (mirrors `Track.albumPath`). */
  albumPath?: string
  /** Live stream indicator when played via `src` (mirrors `Track.live`). */
  live?: boolean
  /** Per-item media-request override when played via `src` (mirrors `Track.request`). */
  request?: TrackRequest
}

export interface Track {
  /**
   * Opaque, stable identifier for this track.
   *
   * The library never parses or derives anything from this value — it is
   * round-tripped verbatim through `setQueue`, `getActiveTrack`, the queue, and
   * `onActiveTrackChanged`, so consumers can recognise which item became active
   * after an external transport change (lock screen / CarPlay / Android Auto /
   * Bluetooth next-previous) without parsing the playable `src`.
   *
   * When present on a playable track it is also the track's *identity* on car
   * browse surfaces: CarPlay's and Android Auto's "now playing" row indicator
   * match the active track to browse rows by `id` (falling back to exact `src`
   * equality for tracks without one). Assign ids when your playable `src`
   * strings for the same item can differ between browse rows and loaded tracks
   * (absolute vs relative URLs, volatile query params) — otherwise the
   * indicator never matches.
   *
   * It is also handed to the per-track `MediaRequestConfig.resolve` and
   * `ArtworkRequestConfig.resolve` hooks, so requests can be built from a stable
   * id (e.g. supply tracks with only an `id` and synthesise `src` in `resolve`).
   *
   * Optional: consumers whose `path`/`src` strings are already stable across
   * surfaces can ignore it.
   *
   * Note: an Android Auto item selected directly from the browse tree that the
   * consumer never queued is identified only by `path`/`src`, so `id` may be
   * undefined on that specific path.
   */
  id?: string

  /**
   * Navigation path. When present, this track is a container (tab, album, playlist, folder)
   * that can be navigated into to view its contents.
   *
   * At least one of `path` or `src` must be defined. When both are set, current
   * surfaces treat the track as playable — `src` wins the rendering, and the
   * browse pipeline replaces a playable track's `path` with its contextual
   * path — so a consumer-supplied `path` on a playable track is not reachable
   * today.
   */
  path?: string

  /**
   * Direct audio source identifier. When present, this track can be played directly.
   *
   * This is typically an absolute URL pointing to an audio resource, but it can also be
   * any string (file path, custom identifier, etc.) that will be passed to
   * MediaRequestConfig.resolve to transform it into the actual media request.
   *
   * At least one of `path` or `src` must be defined.
   */
  src?: string

  /**
   * Artwork URL for the item, or a URL per appearance.
   *
   * The artwork URL is also returned by native in the `artworkSource` property
   * for use in an `<Image>` component, which will contain the transformed version
   * if `artwork` configuration is set in `BrowserConfiguration` or a route.
   *
   * Pass an {@link ArtworkVariants} pair to supply genuinely different images
   * per appearance — a logo that changes colour, say. For a monochrome glyph
   * that only needs recolouring, prefer a single URL plus
   * {@link Track.artworkCarPlayTinted}, which is one fetch instead of two.
   *
   * **iOS:** supports {@link https://developer.apple.com/sf-symbols/ | SF Symbols}
   * with the `sf:` prefix and optional color params:
   * `sf:heart.fill?bg=#FF0090&fg=#fff`. `bg` sets the background color
   * (transparent if omitted), `fg` sets the symbol color (black if omitted).
   * On CarPlay, SF Symbols without explicit colors automatically adapt to
   * light/dark mode.
   */
  artwork?: TrackArtwork

  /**
   * Ready-to-use image source for React Native's `<Image>` component.
   *
   * **Output only** - automatically populated when tracks are retrieved from
   * AudioBrowser. Do not set this manually.
   *
   * Contains the transformed URL and any required headers based on the
   * `artwork` configuration in `BrowserConfiguration`.
   *
   * @example
   * ```tsx
   * <Image source={audioBrowser.activeTrack?.artworkSource} />
   * ```
   */
  readonly artworkSource?: ImageSource

  /** Per-track media-request override; merged last (request → media → track.request). */
  request?: TrackRequest

  /**
   * Whether this artwork should be tinted based on CarPlay's current appearance (light/dark mode).
   *
   * When `true`, the artwork is treated as a monochrome icon and tinted:
   * - Light mode: tinted black for visibility on light backgrounds
   * - Dark mode: tinted white for visibility on dark backgrounds
   *
   * This is useful for SVG or PNG icons that need to adapt to CarPlay's light/dark themes.
   * Full-color artwork (album covers, logos) should not use this.
   *
   * **iOS CarPlay only** - Android Auto is dark-only, so content providers should
   * provide appropriately colored icons directly (e.g., white icons).
   *
   * @default false
   */
  artworkCarPlayTinted?: boolean

  // type?: TrackType
  /** Primary line shown for this item, in both browse lists and now-playing. */
  title: string

  /**
   * Secondary line shown for this item in **browse lists** (CarPlay list detail
   * text, Android Auto list subtitle). Free-form, per-context display text — set
   * it to whatever the row should show, or leave it unset for a blank line. It is
   * *not* shown on the now-playing screen; use {@link artist} for that.
   */
  subtitle?: string

  /**
   * Secondary line shown on the **now-playing / lock-screen** UI, and the value
   * sent to Bluetooth / car head units — it maps to the platform's "artist"
   * metadata slot.
   *
   * Distinct from {@link subtitle}: `artist` drives the now-playing line,
   * `subtitle` drives browse-list rows. Neither falls back to the other — set
   * each for the surface you want it on.
   */
  artist?: string

  /**
   * Browse path the {@link album} line navigates to (same path namespace as
   * {@link path}). On CarPlay, when the active track has an `albumPath`, its
   * album line on the Now Playing screen becomes tappable and navigates the
   * browse stack there. See also `resolveAlbumPath` in the browser
   * configuration for a dynamic fallback.
   *
   * Requires {@link album} to be set: CarPlay renders the tappable line from
   * the album metadata — without an album there is no line to tap.
   *
   * @platform ios
   */
  albumPath?: string

  album?: string
  description?: string
  genre?: string

  /**
   * Duration in seconds, as catalog metadata for app UI (e.g. an episode
   * list). Does not drive the now-playing scrubber — every platform surface
   * derives elapsed/duration from the player itself.
   */
  duration?: number

  /**
   * Display style for this item in Android Auto/AAOS.
   * - 'list': Display as a list row
   * - 'grid': Display as a grid tile
   *
   * On Android: when `artwork` is an `android.resource://` URI
   * (e.g., `android.resource://com.myapp/drawable/ic_folder`), the library
   * automatically uses 'category' styling which adds margins around the icon
   * and enables system tinting for vector drawables.
   *
   * @see https://developer.android.com/training/cars/media#default-content-style
   */
  style?: TrackStyle

  /**
   * Display style for this item's children in Android Auto/AAOS.
   * Only applies to browsable items (containers/folders).
   * - 'list': Display children as list rows
   * - 'grid': Display children as grid tiles
   *
   * Must be set on the item when it appears as a child in a parent's list.
   * Android Auto reads the extras at that point to determine how to display
   * the folder's contents when navigated into.
   */
  childrenStyle?: TrackStyle

  /**
   * Whether this track is favorited. When the `favorite` capability is enabled,
   * displays a filled/empty heart icon in media controllers (notification, Android Auto).
   */
  favorited?: boolean

  /**
   * Group title for section headers in Android Auto/AAOS.
   * Items with the same groupTitle are displayed under a shared section header.
   * Items must be contiguous to form a single group.
   */
  groupTitle?: string

  /**
   * Whether this track is a live stream. When true, displays a "live" indicator
   * in iOS now playing interfaces.
   */
  live?: boolean

  /**
   * When present, renders this track as a horizontal row of tappable artwork
   * thumbnails instead of a standard list item.
   *
   * - Track.title → row header text
   * - Track.path → navigated when header is tapped (optional)
   * - Each ImageRowItem → one thumbnail in the horizontal row; a thumbnail
   *   with `src` plays immediately on tap, otherwise its `path` is navigated
   *
   * On CarPlay: maps to CPListImageRowItem. Limits visible images by display
   * width (~4-5). Excess silently truncated. A row whose track has no `path`
   * is a pure preview: its header is not tappable.
   *
   * On Android Auto: there is no image-row rendering, so the row expands into
   * its items as grid-styled rows (artwork tiles where the host honors the
   * per-item content-style hint, list rows otherwise) grouped under the
   * track's title, followed by the track itself as a browsable "view all"
   * link when it has a `path`.
   *
   * On app side: consumable by React Native UI for horizontal thumbnail layouts.
   */
  imageRow?: ImageRowItem[]
}

/**
 * A Track that has been resolved with its children through browsing.
 *
 * This is the return type when browsing a track - it includes the track's
 * metadata along with its immediate children.
 *
 * @example
 * ```ts
 * // navigate() is fire-and-forget (returns void); read the resolved page
 * // from getContent() / useContent() / onContentChanged.
 * navigate('albums/abbey-road');
 * const resolved = getContent();
 * console.log(resolved?.path); // "albums/abbey-road"
 * console.log(resolved?.title); // "Abbey Road"
 * console.log(resolved?.children); // Array of tracks in this album
 * ```
 */
export interface ResolvedTrack extends Track {
  /**
   * Browse path of this resolved track. Always present since you navigated to this location.
   */
  path: string

  /**
   * Immediate children of this track. Present for container tracks (albums, playlists, folders).
   * Undefined for leaf tracks (individual songs without children).
   */
  children?: Track[]

  /**
   * Shows the "Ask Siri to Play Audio" assistant cell on the CarPlay list template
   * for this content. Requires the Siri entitlement, the `INPlayMediaIntent` keys in
   * Info.plist, and an in-app intent handler in your `AppDelegate`
   * (`application(_:handlerFor:)`) — no separate Intents Extension is needed. See the
   * setup guide for the full wiring.
   *
   * - `'top'`: Shows at the top of the list
   * - `'bottom'`: Shows at the bottom of the list
   *
   * @see {@link https://audiobrowser.dev/guide/carplay#siri-voice-search | CarPlay Siri Voice Search guide}
   * @platform ios
   */
  carPlaySiriListButton?: CarPlaySiriListButtonPosition
}
