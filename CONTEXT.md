# react-native-audio-browser

Domain glossary for the library. Audience: contributors working across the TypeScript, Kotlin, and Swift layers, plus library users integrating it into an app.

This file is a glossary, not a spec. For architectural decisions see `docs/adr/`. For implementation details see the per-platform `CLAUDE.md` files (`ios/CLAUDE.md`, `website/CLAUDE.md`) and `ios/ARCHITECTURE.md`.

## Language

### Top level

**AudioBrowser**:
The library, and the JS namespace through which its public API is reached. Owns the **Browser** and the **Player**.
_Avoid_: Module, SDK, AudioModule.

**Browser**:
The navigation subsystem within AudioBrowser, exposing a single tree that powers both in-app browsing and the browse views of External surfaces (Android Auto, CarPlay). Owns the **BrowseTree**, **Routes**, **Tabs**, and **Search**.
_Avoid_: Navigation, MediaBrowser (the Android platform class).

**Player**:
The playback subsystem within AudioBrowser. Receives Playable Tracks and streams them.
_Avoid_: Engine, AudioEngine.

### Navigation

**Path**:
A position in the **BrowseTree**, expressed as a slash-delimited string (e.g. `/albums/abbey-road`). Passed to `navigate(path)`; **Routes** match against Paths to resolve content. See "Flagged ambiguities" — distinct from the HTTP `RequestConfig.path`.
_Avoid_: URL, route, address (when referring to the tree position).

**Route**:
A binding from a path pattern to a **BrowserSource**. Patterns support `{param}`, `*`, and `**`; the most specific match wins.
_Avoid_: Endpoint, handler, mapping.

**Tab**:
A top-level navigation entry shown in the tab bar of the browser UI. A Tab is not a **Section** — Sections live inside pages.
_Avoid_: Section, category.

**Search**:
The voice- and text-driven query subsystem. Receives structured `SearchParams` — one cross-platform shape normalized from both iOS (SiriKit `INMediaSearch`) and Android (`MEDIA_PLAY_FROM_SEARCH`) voice/text intents — and returns a `Track[]`. The per-platform parsers absorb the wire differences; the `SearchParams` they emit is identical. Within it, **`mode`** is the _container vertical_ (what kind of result: station / podcast / song / …), orthogonal to the _filter_ props (`genre` / `artist` / `album`); **`reference`** is the media-reference axis (`'my'` = the user's own collection, routed to Search; "currently playing" is resolved natively and never reaches Search).
_Avoid_: Query, lookup. For `mode`, avoid conflating the vertical with the filter props.

**BrowserSource**:
Anything that can produce children for a path — the value on the right-hand side of a **Route**, or of `browse:` / `tabs:` / `search:`. Comes in three shapes, all producing a `ResolvedTrack`: a static `ResolvedTrack` with its children declared inline, a callback that returns one, or a `TransformableRequestConfig` that points at a JSON `ResolvedTrack`-shaped endpoint.
_Avoid_: Provider, handler.

### Tree

**BrowseTree**:
The navigable tree of Tracks exposed by the Browser. Browsable Tracks have children; Playable-only Tracks do not.
_Avoid_: Content tree, media tree, hierarchy.

**Track**:
The universal content type in the BrowseTree. A single `Track` can be **Browsable**, **Playable**, or both, depending on which fields are set. The name is a misnomer carried for platform-alignment reasons — see "Flagged ambiguities".
_Avoid_: Item, Node, MediaItem (those names belong to the platform SDKs). In public prose, lowercase “entry” is acceptable as a loose UI noun when the concrete content kind is unknown, but **Entry** is not a domain type.

**Browsable**:
A Track that has a `path` and resolves to children when navigated into. A _shape_ of Track, not a separate type.
_Avoid_: Folder, Container, Directory.

**Public prose note**:
In guides, use **Browsable** for the abstract shape: a Track with a `path` that opens a Path and resolves to children. Do not replace it with a universal noun such as Folder, Container, Directory, Node, or Item.

When referring to a concrete thing in the BrowseTree, prefer the domain noun the integrator or listener would recognize: tab, album, playlist, category, show, station, collection, etc. Use lowercase “entry” only as a loose prose fallback when the concrete kind is unknown; do not promote **Entry** to a glossary term.

Keep the layers distinct:

- A **Tab** is a top-level navigation entry.
- A **Path** is the slash-delimited address navigated to.
- A **Route** is the binding that resolves a Path.
- A **Browsable Track** is the Track shape with a `path`.
- The resolved children for a Path are its content, not the tab or route itself.

Example:

- Prefer: “A child with a `path` is browsable and opens another Path.”
- Prefer: “Re-fetch the `/favorites` content.”
- Avoid: “A child with a `path` is a folder.”
- Avoid: “Re-fetch the Favorites tab.”

**Playable**:
A Track that has a `src` and can be streamed by the player. A _shape_ of Track, not a separate type. A Track _may carry_ both `path` and `src`, but current surfaces treat such a track as playable: `src` wins the rendering (CarPlay row style, Android Auto's mutually-exclusive isPlayable/isBrowsable flags), and the browse pipeline replaces a playable track's `path` with its contextual path anyway. Genuinely combined items — tap to play _or_ drill in — are a future item (see FUTURE.md, "Browsable + Playable Combined Items").
_Avoid_: Leaf, Song, Media.

**Identity**:
What makes two Tracks the same item: the Track's `id` when set (non-blank), falling back to its `src`. The single comparison rule everywhere Tracks are compared — favorites matching, the CarPlay / Android Auto now-playing row indicator, section scoping and skip-in-place, and contextual queue re-expansion. Compared whole: never a substring, never parsed out of a URL. A Browsable-only Track has no Identity — it is addressed by its **Path**.
_Avoid_: key, uid (a consumer-side concept).

**Contextual Path**:
The `path` the browse pipeline stamps onto a Playable Track: `{parentPath}?__trackId={identity}&__index={position}`. Carries the page the track was rendered on so a later tap or resumption can re-expand its queue (ADR 0006). `__trackId` is the **Identity** check; `__index` — the flat page position at stamp time (children concatenated in **Section** order) — is only a tie-breaker between surfaces carrying the same Identity, so a stale index can never select a different track (ADR 0009).
_Avoid_: contextual URL in new prose (legacy alias in code comments), deep link.

**Section**:
A titled, styled group of Tracks within a resolved page — the unit of queue scope, declared, not derived and never rendering-dependent (ADR 0010). A page's canonical shape is `sections`; a plain `children` list is authoring sugar for one untitled Section. Its **Style** block declares the requested presentation; each surface renders its nearest supported form.
_Avoid_: group, category; Section for a **Tab** (Tabs are top-level navigation, Sections live inside pages).

**ResolvedTrack**:
The return type of `navigate()` — a Track that has gone through the browse pipeline. Compared to the declared **Track** form an app/API supplies, a ResolvedTrack carries the transformed `artworkSource` (ready for `<Image>`), an optionally hydrated `favorited` flag, and — for Browsable Tracks — populated `sections` (a page authored with plain `children` resolves to one untitled **Section**). Media URLs are not part of resolution; they're transformed at playback time.
_Avoid_: ExpandedTrack, LoadedTrack.

**Page**:
The resolved form of a Browsable Track — what `navigate()` returns for it (**ResolvedTrack**). A Page is the Track, now open, acting as the container of its **Sections**: its **Style** block is a container's block, declaring for everything on the page. Not a separate kind of thing — a Track in its container role, which begins at resolution; before that, the Track is a handle whose block styles only the handle.
_Avoid_: screen, folder, level, "defaults tier" (a Page is a container, not special styling machinery).

### Style

**Style**:
The declaration block of presentation properties carried by a **Track**, a **Section**, or a **Page** (`style`). Declarations are aspirational: each surface renders the properties it understands and ignores the rest — inert, never an error. Presentation-only: no style property affects queue scope, playback, or navigation. Facts with behavioral weight (**Disabled**, **Favorited**) are Track fields, not style.
_Avoid_: hint (the retired platform-prefix era), flag, option.

**Inherited property**:
A style property whose value flows to the items within a **Page** — track ?? section ?? page — unless a closer block declares its own (`imageShape`, `artworkRendering`, `accessorySymbol`, `cardTint`, `cardImage`). Admission test for any future one: "if set on a browsable parent, should its resolved descendants inherit it unless overridden?" Inheritance never crosses resolution.
_Avoid_: cascading (CSS's cascade resolves competing declarations for one element — this model has no competing declarations, only inheritance and scope).

**Container property**:
A style property stating a fact about a container rather than its items (`display`, `gridWrap`, `gridTile`). Resolved by scope override, not inheritance: the **Page** declares for its whole scope, a **Section** overrides for its own children — two declarations about the same rendering decision at different widths, the narrower winning. `display` is positional: each holder describes its own children, never its descendants.
_Avoid_: fallback (the section isn't missing anything), inherited (container properties never flow to items).

**Disabled**:
A content fact on a **Track**: the item is unavailable. A Disabled Track never plays from any surface — tap, auto-advance, voice selection, and queue expansion all skip it. Where a surface can draw unavailability it renders grayed and inert; where it can't, the Track is hidden — never a normal-looking dead control. A fact, not a **Style**: it travels on the Track.
_Avoid_: grayed-out, hidden (renderings of the fact, not the fact), isEnabled (the platform knob it maps onto).

### Requests

**Resolve**:
An optional per-Track callback that produces a RequestConfig from a Track's metadata. Used by the `media` and `artwork` request pipelines when the request needs Track fields (artist, album, src) to be built.
_Avoid_: Build, generate.

**Transform**:
The final step in any outbound request, applied to the merged RequestConfig just before it goes out. Used to sign URLs, attach auth tokens, or fold in size hints. Optional.
_Avoid_: Finalize, sign, decorate.

**Request-Config Layer**:
One config in the outbound-request stack, applied base-up: **request** (shared) → **kind** (browse / search / media) → **route** (per-Route). A Request-Config Layer with a **Transform** replaces the running config entirely (transform-wins); otherwise its static fields merge over it — except `path`, which is carried from the base (only a Transform may change it). `buildApiRequest` / `applyLayers` are the canonical application of the stack. Artwork configs are deliberately _not_ applied as Request-Config Layers: their static fields merge override-wins, so a `nowPlayingArtwork` like `{ path: "/artwork/{id}" }` can supply the path.
_Avoid_: Layer (unqualified — too generic), stage, level.

### Playback

**Queue**:
The Player's working list of Tracks. Has a current index (the **Active Track**), a repeat mode, and a shuffle order.
_Avoid_: Playlist (a playlist is a Browser concept — user-curated content), playback list.

**Active Track**:
The Track at the Queue's current index. The Track that is (or would be) playing. Distinct from **Now Playing** — Active Track is queue state, Now Playing is display metadata.
_Avoid_: Current Track, Playing Track.

**Now Playing**:
The display metadata surfaced on lockscreen / notification / CarPlay / Android Auto. Mirrors the **Active Track**'s metadata by default; the app may override it via `updateNowPlaying()` (the live-stream use case: the station stays as the Active Track while Now Playing reflects the current song).
_Avoid_: Lockscreen info, Notification info, Media metadata.

### Playback state

Three closely-named concepts that sound interchangeable but are not.

**PlaybackState**:
The player's state machine label. One of six values: `idle`, `stopped`, `loading`, `playing`, `paused`, `error`. Low-level — UI code should usually consume `PlayingState` instead.
_Avoid_: PlayerState, EngineState.

**playWhenReady**:
Whether the user wants playback to start automatically when the Active Track has loaded and buffered. Calling `play()` sets it to `true`, `pause()` to `false`. Independent of `PlaybackState`.
_Avoid_: AutoPlay, ShouldPlay.

**PlayingState**:
The UX-level state: a struct of two booleans `{ playing, buffering }`. Derived from `PlaybackState` + `playWhenReady`. What a play/pause button and loading spinner should bind to.
_Avoid_: PlaybackStatus, PlayingFlags, UIState.

### Playback failure

**PlaybackErrorKind**:
The normalized, cross-platform classification of why playback failed. Each platform maps its own native failure onto this set (AVFoundation on iOS, ExoPlayer on Android, Shaka on web), so an app can branch on it without knowing which engine produced it. **The only part of a PlaybackError that may drive user-facing copy.** The parallel of `NavigationErrorType` on the browsing side.
_Avoid_: error type, error category, error reason.

**PlaybackError code**:
The raw native failure identifier carried alongside the Kind, for diagnostics and telemetry only. Deliberately _not_ a contract: its values are the underlying engine's own, so they differ per platform and change with it. Never branch on it, never show it.
_Avoid_: treating it as an enum.

A failure carrying no evidence of a cause takes the catch-all Kind rather than being guessed into a more specific one. A wrong classification both misleads the listener and corrupts the telemetry aggregates the Kind exists to make possible.

**Load**:
One track's playback session: created when a track becomes current (selection, queue advance, skip) or restarted from a terminal error (`retry()`, or play while in `error`), surviving every _automatic_ retry reload of that track. The unit that retry budgets, the **hasPlayed** flag, and advisory-error deduplication are scoped to. A new load starts fresh; retries within one don't.
_Avoid_: request, attempt (an attempt is one try _within_ a load).

**Advisory (retrying) error**:
A classified PlaybackError surfaced _while automatic retry is still working on it_ — `retrying: true`, attached via `onPlaybackChanged` to a non-terminal playback state, so UIs can show the cause over a spinner. Provisional by definition: it clears when playback recovers, or hardens into a terminal error (state `error`, `retrying` absent) when the retry budget runs out. Terminal errors alone fire `onPlaybackError`.
_Avoid_: warning, soft error, pending error.

**First-connect budget** / **Recovery budget**:
The two duration bounds on automatic retry, selected per load by whether it has ever rendered audio (**hasPlayed**): a short budget (default 12s, counting only a contiguous online stretch) for a load that never played, the long one (default 2 min) once playback has proven the source works. Durations bound the give-up promise; attempt counts only pace the backoff. See ADR 0004.
_Avoid_: retry limit, timeout (both suggest a single number).

### Metadata

**TrackMetadata**:
Static metadata extracted from a media file's container or tag frames (ID3, MP4 atoms, etc.) at load time. Distinct from a **Track**'s own app-provided fields, which share names like `title`, `artist`: TrackMetadata is what the _file says about itself_, Track fields are what the _app declared_.
_Avoid_: TrackInfo, MediaMetadata.

**TimedMetadata**:
Metadata streamed mid-playback — ICY frames from Shoutcast/Icecast or in-band ID3 frames from HLS. The library does not auto-apply it to **Now Playing**; the app forwards selected fields via `updateNowPlaying()`. This is the live-radio data flow.
_Avoid_: StreamMetadata, ID3Event.

**Artwork**:
The image that _represents_ a **Track** or **Section** — album art, a station logo, a tab's symbol standing in for the tab's content. Iconography that _annotates_ an item rather than represents it — accessory badges, button glyphs, playing indicators — is a **symbol** (an SF Symbol name). "Image" is the generic word, not a banned one: an Artwork is an image, and layers that handle any picture (`ImageSource`, a platform `UIImage`) rightly say so. Library-defined names prefer the most specific applicable role (`artwork`, `artworkSource`, `artworkRendering`); passthrough properties that mirror a platform knob keep that platform's own term (`imageShape` is CarPlay's word) — the property's meaning is the platform's rendering contract, so the platform's word is the honest one.
_Avoid_: icon (say Artwork or symbol, by role), thumbnail, cover.

### External surfaces

**External surface**:
Any non-app integration that the library drives or receives input from. Includes: iOS lockscreen / Control Center, Android notification, **CarPlay**, **Android Auto**, **Wear OS**, **AAOS** (Android Automotive), and Bluetooth / car head units. Surfaces vary in capability — some browse the tree, some only show Now Playing, some only send Remote commands.
_Avoid_: External controller, media controller (both are used in the codebase but conflate display-only, browse-capable, and input-only surfaces).

**Remote command**:
An input event from an External surface — play, pause, next, previous, seek, favorite, etc. Surfaced via `onRemote*` callbacks; optional `handleRemote*` callbacks let the app override default Player behaviour.
_Avoid_: Remote event, remote action.

**Capability**:
A flag controlling whether a specific control is _available_ on External surfaces. Disabling a Capability hides the corresponding control and prevents the matching **Remote command** from firing. Distinct from Remote command: a Capability is what's _configured_, a Remote command is the _event_ fired when an available Capability is invoked.
_Avoid_: Permission, Feature flag, Control.

**Remote button**:
A button an External surface draws, which emits a **Remote command** when tapped — skip, jump, favorite. Distinct from a **Capability**: a Capability decides whether the button may exist at all, a Remote button is the thing rendered. Android only; CarPlay's now-playing buttons are configured separately.
_Avoid_: Notification button, player button, control button.

**Remote button layout**:
The arrangement of **Remote buttons** on Android, published once and honoured by every Android External surface — notification, Android Auto, and the Android 13+ system media controls. Has exactly three positions: `back` and `forward` either side of play/pause, and `overflow` for the rest. A layout describes the whole arrangement; omitting it derives one from **Capabilities**.
_Avoid_: Notification buttons, slots (a Media3 implementation term — `back`/`forward`/`overflow` are the domain names).

**Favorited**:
A boolean on a Track marking it as a user favorite. Toggled programmatically or via the heart button on an External surface. The library's domain vocabulary has no Rating concept.

A favorites collection is app-owned/user-owned content. The library tracks **Favorited** state on Tracks and keeps surfaces in sync; the app owns where the favorites collection is stored, how it is persisted, and how a favorites Path resolves to Tracks. Hydration matches the app's declared identifiers against each Track's **Identity**; a caller-set `favorited` on a Track wins over hydration for display but never fills the cache.
_Avoid_: Rating, hearted, liked, starred.

**Browse Gate**:
An app-imposed block on browsing from External surfaces, set and cleared at runtime. While gated, **Tabs** stay visible but every tab's content is replaced by a single message — rendered as each surface allows (a full-page view on CarPlay, a list tile on Android Auto) — and **Search** from External surfaces resolves to the same message. The **Player**, the **Queue**, and **Now Playing** are unaffected: a gate blocks _finding_ content, never _hearing_ it. Generic by design — subscription, login, and region blocks are all Browse Gates.
_Avoid_: Paywall (one app's reason for a gate, not the concept), error page (a gate is deliberate app state, not a **NavigationError**), lock screen (that's an External surface).

## Relationships

- A **Browser** holds zero or more **Routes**, up to four **Tabs**, and optionally one **Search**.
- The **Browser** produces **ResolvedTracks**; the **Player** consumes their Playable **Tracks** via the **Queue**.
- A **Queue** holds zero or more **Tracks** and has at most one **Active Track**.
- A live stream emits **TimedMetadata**; the app may forward fields into the **Now Playing** override.
- The `media` and `artwork` request pipelines accept a per-Track **Resolve**; all requests accept a final **Transform**.
- **External surfaces** display **Now Playing**, may browse the **BrowseTree**, and emit **Remote commands**.
- A **Capability** controls whether a matching **Remote command** can be invoked from an External surface.
- A **Browse Gate** blocks the **BrowseTree** and **Search** on External surfaces, but never the **Player**, the **Queue**, or **Now Playing**.
- A Track is **Favorited** independently of being the Active Track — favoriting is set on the Track, not on the Queue.
- A favorites collection belongs to the app/user; **Favorited** is the per-Track state the library keeps synchronized across surfaces.
- A **Page** is a Browsable **Track** in its container role; its **Style** declares for the whole page, a **Section**'s overrides it for that section's children, and a Track's styles the Track itself.

## Example dialogue

> **Contributor:** "My radio integration calls `updateNowPlaying({ title })` when stream metadata arrives, but the notification keeps flickering back to the station name."
>
> **Maintainer:** "**Now Playing** snaps back to mirror the **Active Track**'s metadata whenever the Active Track changes. For a stream, the Active Track is the station — don't replace it when metadata arrives; just keep calling `updateNowPlaying`. The two diverge by design: the Active Track is _what's in the Queue_, Now Playing is _what's currently being heard_."
>
> **Contributor:** "If I put a remote artwork URL in the override, does my **Transform** still sign it?"
>
> **Maintainer:** "No — a Now Playing override bypasses the request pipeline entirely. The **External surface** fetches the URL directly, so bake any auth into the URL or use a signed CDN."

## Flagged ambiguities

- **`src` vs `path` on a Track.** Both are string fields and easy to mix up. `path` is the _navigation_ address in the BrowseTree — its presence makes the Track **Browsable**. `src` is a _media_ identifier (usually an audio URL) — its presence makes the Track **Playable**. A Track may carry both, but surfaces currently treat that as playable — `src` wins; see **Playable**. When in doubt: ask "do I navigate into this or stream this?"

- **`id` is the Playable Track's Identity when present.** A Track's **Identity** is its `id` when set (non-blank), falling back to its `src` — one rule, applied at every comparison site (favorites, the now-playing row indicator, section scoping, skip-in-place, contextual queue re-expansion). The fallback is per-Track, not per-comparison: a Track carrying an `id` compares by that id alone, so a row with an `id` never matches a track that only has a `src` — mixed id-presence never matches. Assign ids consistently, everywhere or nowhere.

- **"path" has two senses, and both are fields named `path`.** A tree address (`/albums/abbey-road`, the navigation primitive, `Track.path`) and an HTTP request path (`/api/v2/albums/123/tracks`, `RequestConfig.path`). The two never co-occur on the same object — a Track is not a RequestConfig — but the same string can appear in both roles when a Route forwards a tree path into an HTTP request. Sharing the name is deliberate: the tree-address field was previously called `url`, which this glossary itself flags as the word to avoid for a tree position. Disambiguate by the owning type, not by renaming.

- **`buffering` is a `PlayingState` flag, not a `PlaybackState` value.** `PlaybackState` has `loading` (covering both initial load and mid-stream rebuffering); `PlayingState` exposes the derived `buffering` boolean for UI binding. "The player is buffering" is a UX statement, not a state-machine claim.

- **"Rating" is a platform concept, not a library concept.** Android (`MediaSession.setRatingType`) and iOS (`MPFeedbackCommand`) expose generalised Rating APIs supporting thumbs / stars / percentages / hearts. This library uses only the heart variant and surfaces it as **Favorited**. The `Rating` types in `src/features/rating.ts` exist as platform-bridge plumbing and are not part of the domain vocabulary.

- **Tabs and Search look like Routes internally.** At the native layer, `tabs:` and `search:` (and the top-level `browse:`) are flattened into the same route table as user-declared routes, using magic paths `__tabs__`, `__search__`, and `__default__`. This is an implementation detail — domain-wise a Tab is not a Route; it's a top-bar navigation entry with a 4-entry constraint, and Search is a voice/text query handler. Don't promote the magic paths to the public vocabulary.

- **"Track" is overloaded.** It names the universal content type in the BrowseTree, but in everyday English a track is a song. A Track called "Jazz" with no `src` and 12 children does not behave like a track. This is intentional — Android Auto's `MediaItem` and CarPlay's `CPListItem` both model the browse tree with a single polymorphic type, and aligning with that shape is cheaper than fighting it. Tracked in [#39](https://github.com/radio-garden/react-native-audio-browser/issues/39).
