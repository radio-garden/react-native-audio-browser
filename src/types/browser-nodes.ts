import type { CarPlaySiriListButtonPosition, HttpMethod } from './browser'

/**
 * How a holder's *children* lay out — CSS's inner display type, honored
 * literally (`display: 'grid'` in CSS describes the children, not the
 * element).
 *
 * - `'list'` (default) — full-width rows on every surface.
 * - `'grid'` — artwork tiles; wraps unless {@link SectionStyle.gridWrap}
 *   is `false`. Tiles presume artwork: an artwork-less child renders a
 *   placeholder tile plus its title. On CarPlay a *wrapping* grid needs
 *   iOS 26+ and renders a list before that; a single-line grid renders
 *   on every OS.
 */
export type StyleDisplay = 'list' | 'grid'

/**
 * How a track's artwork is rendered.
 *
 * - `'original'` (default) — drawn as-is.
 * - `'stencil'` — treated as a monochrome glyph and tinted to the
 *   surface's appearance (black in light mode, white in dark). For
 *   monochrome logos and icons; full-color artwork should stay
 *   `'original'`.
 */
export type ArtworkRendering = 'original' | 'stencil'

/**
 * The style declaration block a {@link Track} may carry: *inherited item
 * properties* (resolved `track ?? section ?? page ?? default`, per
 * property — they travel with the track) plus the *positional*
 * {@link TrackStyle.display} (each holder's own children; never
 * inherited).
 *
 * Rules governing every property:
 *
 * - **Declarations are aspirational.** Each surface renders the
 *   properties it understands and ignores the rest — inert, never an
 *   error.
 * - **Presentation only.** No style property affects queue scope,
 *   playback, or navigation: rendering may truncate what is visible, it
 *   never changes what plays. (This is why {@link Track.disabled} is NOT
 *   here — it carries behavior, so it lives on Track as a content fact.)
 * - **The inheritance boundary**: a handle's block styles the handle; a
 *   page's block is inherited by the page's descendants; nothing
 *   inherits across resolution.
 */
export interface TrackStyle {
  /**
   * How this entity's *children* lay out. The meaning is uniform at
   * every position; only the children differ:
   *
   * - On a {@link Section}: the section's children.
   * - On a resolved page ({@link ResolvedTrack.style}): the declaration
   *   for the whole page — a section overrides it for its own children.
   * - On a browsable {@link Track} (the handle): the *advertised*
   *   layout of the page it opens. Android Auto decides a page's
   *   layout before resolving it, so it is the one reader that acts
   *   on the handle's declaration. Declared or it doesn't exist: no
   *   value → nothing advertised. The resolved page is the truth.
   * - On a track rendered *playable* (`src` wins the rendering): inert —
   *   playables open no page.
   *
   * Never *item*-inherited — each holder speaks only for its own
   * children. Between containers it resolves by scope override, not
   * inheritance: the page declares for its whole scope, a section
   * overrides for its own children.
   *
   * @default 'list'
   *
   * @see https://developer.android.com/training/cars/media/create-media-browser/content-styles
   */
  display?: StyleDisplay

  /**
   * Inherited (`track ?? section ?? page`): how this item's artwork is
   * rendered — `'stencil'` tints a monochrome glyph to the surface's
   * light/dark appearance.
   *
   * @default 'original'
   *
   * @platform carplay
   */
  artworkRendering?: ArtworkRendering
}

/**
 * A {@link Section}'s (or resolved page's) style declaration block: the
 * *container* properties below, plus — via `extends` — a section-wide
 * value for every inherited {@link TrackStyle} item property (and the
 * section's own positional `display`).
 *
 * Container properties resolve by scope override, not inheritance: the
 * page declares for its whole scope, a section overrides for its own
 * children (`section ?? page ?? default`) — they never flow to items.
 */
export interface SectionStyle extends TrackStyle {
  /**
   * Whether grid tiles wrap to multiple lines. `false` renders exactly
   * one line — the teaser shelf: a preview of a larger collection,
   * typically paired with {@link Section.path} as the "view all" target.
   *
   * Rendered by CarPlay (a single line of tiles). Android Auto has no
   * single-line tile container — its grid wraps, showing more, never
   * less. Phone UIs typically honor `false` as a horizontal scroller.
   *
   * Inert unless {@link TrackStyle.display} is `'grid'`.
   *
   * @default true
   *
   * @platform carplay
   *
   * @see https://developer.apple.com/documentation/carplay/cplistimagerowitem
   */
  gridWrap?: boolean
}

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

/**
 * A titled, styled group of Tracks within a resolved page — the unit of
 * queue scope. Tapping a playable child queues the section it sits in,
 * identically on every platform and screen width: rendering may truncate
 * what is visible, it never changes what plays.
 */
export interface Section {
  /** Header text. Absent = headerless group. */
  title?: string
  /**
   * Secondary line for the section's navigation surface — e.g. the label
   * of the "view all" link a tile section gets on Android Auto.
   */
  subtitle?: string
  /**
   * Presentation, separated from content. Each surface renders the
   * declared layout's nearest supported form; on CarPlay
   * a wrapping grid requires iOS 26+ and renders a list before that,
   * since CarPlay's only earlier tile container truncates at a width the
   * system doesn't report. Use the artwork `resolve` hook to supply
   * fallback art for tile layouts.
   */
  style?: SectionStyle
  /**
   * Navigation target for the section header / "view all" surface. A
   * tile section's header tap (CarPlay) and appended "view all" link
   * (Android Auto) navigate here. Absent = a pure preview; the header is
   * not tappable.
   */
  path?: string
  /** The section's tracks. */
  children: Track[]
}

/**
 * The one node type of the browse tree — every tab, folder, station,
 * episode, and queue item is a Track. What a Track *is* follows from which
 * address fields it carries: a {@link path} makes it **browsable** (it opens
 * as a page), a {@link src} makes it **playable** (it can be loaded into the
 * player); at least one of the two is required. The remaining fields are
 * display metadata ({@link title}, {@link artist}, {@link artwork}, …) and
 * content state ({@link favorited}, {@link live}, {@link disabled}).
 */
export interface Track {
  /**
   * Opaque, stable identifier for this track.
   *
   * A track's **identity** is `id` when set (non-blank), falling back to
   * `src`. That single rule drives every comparison the library makes:
   * favorites matching (`setFavorites`), the CarPlay / Android Auto
   * "now playing" row indicator, section scoping and skip-in-place, and the
   * contextual queue re-expansion. Two tracks refer to the same item iff
   * their identities are equal — so set `id` consistently on every surface,
   * or on none.
   *
   * The library never parses or derives anything from this value — it is
   * round-tripped verbatim through `setQueue`, `getActiveTrack`, the queue,
   * `onActiveTrackChanged`, and `onFavoriteChanged`, and handed to the
   * per-track `MediaRequestConfig.resolve` / `ArtworkRequestConfig.resolve`
   * hooks so requests can be built from the stable id.
   *
   * Optional: consumers whose `src` strings are identical wherever the same
   * item appears can ignore it — their identity is the `src`. Assign ids when
   * `src` strings for the same item can differ between surfaces (absolute vs
   * relative URLs, volatile query params), or when favorites are stored as an
   * identifier that isn't the playable URL.
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
   * `style: { artworkRendering: 'stencil' }`, which is one fetch instead
   * of two.
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

  /**
   * Album name, shown on now-playing surfaces that have an album slot
   * (CarPlay, Android Auto, Bluetooth metadata — the iOS lock screen has
   * none). With {@link albumPath} set, CarPlay renders it as a tappable
   * line that navigates there.
   */
  album?: string

  /**
   * Longer free-form text about the item, passed to the platform's
   * description metadata slot where one exists. Not shown on browse rows
   * (those render {@link title} / {@link subtitle}) or the lock screen.
   */
  description?: string

  /**
   * Genre name, passed to the platform's genre metadata slot where one
   * exists. Catalog metadata only — the library never matches search or
   * browse against it.
   */
  genre?: string

  /**
   * Duration in seconds, as catalog metadata for app UI (e.g. an episode
   * list). Does not drive the now-playing scrubber — every platform surface
   * derives elapsed/duration from the player itself.
   */
  duration?: number

  /**
   * The track's style declaration block: inherited item properties (a
   * per-item override of section/page values — they travel with the
   * track), plus the positional {@link TrackStyle.display} — on a
   * browsable track, the advertised layout of the page it opens, read
   * by Android Auto, which picks a page's layout before resolving it.
   */
  style?: TrackStyle

  /**
   * Unavailable — a content-state fact beside `favorited` and `live`,
   * with full behavioral weight (content facts may carry behavior; style
   * never does): a disabled track never plays — tap refused, queue
   * expansion excludes it, voice search won't match it.
   *
   * Rendering ladder, never a trap: **grayed + inert** where the surface
   * can draw it (CarPlay) → **hidden** where it can't (Android Auto) —
   * never a normal-looking dead control. Hiding is behavior-safe because
   * the track is inert everywhere regardless; the cost is losing the
   * "Available at 9 PM" tease on surfaces that hide, so put the reason
   * in the title for the ones that gray. Applied at page (re-)serve,
   * like style.
   *
   * @default false
   */
  disabled?: boolean

  /**
   * Whether this track is favorited. When the `favorite` capability is enabled,
   * displays a filled/empty heart icon in media controllers (notification, Android Auto).
   */
  favorited?: boolean

  /**
   * Declares this track a live broadcast rather than a file. The players
   * detect *transport* liveness (sliding windows, buffering) themselves;
   * this declaration drives the semantic decisions they can't infer:
   *
   * - **End of stream** (iOS): a live track "ending" is judged a dropped
   *   broadcast and recovered by rejoining the edge, never a queue advance.
   * - **Stall recovery** (iOS): a live track that runs dry reconnects at
   *   the edge; a non-live one resumes its existing connection.
   * - **`seekToLiveEdge()`** (all platforms): the call only acts on a
   *   track declared live — without the declaration it is a no-op.
   * - **Persistence** (iOS): no position is stored, so a cold-start resume
   *   rejoins the current broadcast instead of seeking a stale timestamp.
   * - **Now-playing** (iOS): the live indicator and live-style
   *   (non-scrubbing) progress UI.
   *
   * The iOS-heavy list is deliberate, not a gap: on Android, ExoPlayer's
   * own classification already covers these — a dropped live connection
   * surfaces as a load error (never end-of-stream), and persistence keys
   * off its detected liveness — so the declaration's only Android reader
   * is the `seekToLiveEdge()` gate. Android Auto has no live badge.
   *
   * Omitting it never breaks playback — it silently breaks these judgment
   * calls. Declare it on every live track.
   */
  live?: boolean
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
 * navigate('/albums/abbey-road');
 * const resolved = getContent();
 * console.log(resolved?.path); // "/albums/abbey-road"
 * console.log(resolved?.title); // "Abbey Road"
 * console.log(resolved?.sections); // the album's tracks, as sections
 * ```
 */
export interface ResolvedTrack extends Track {
  /**
   * Browse path of this resolved track. Always present since you navigated to this location.
   */
  path: string

  /**
   * A page is a Track that is also the container of its sections, so its
   * block widens to {@link SectionStyle}: the declaration for the whole
   * page. Item properties set here are inherited by every item on the
   * page unless a section or track declares its own; the container
   * property (`gridWrap`) and the positional `display` are the page-wide
   * values a section overrides for its own children.
   */
  style?: SectionStyle

  /**
   * The page's sections — the canonical resolved shape. Every resolved page
   * with content carries `sections`; a page authored with plain `children`
   * resolves to a single untitled section.
   */
  sections?: Section[]

  /**
   * Authoring sugar for a flat page: equivalent to declaring one untitled,
   * style-less section holding these tracks (which therefore takes the
   * page block's scope-wide values, like any undeclared section). Accepted
   * anywhere a page is authored (static routes, browse callbacks, JSON
   * payloads); never populated on a *resolved* page — read `sections`
   * instead.
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
