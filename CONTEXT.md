# react-native-audio-browser

Domain glossary for the library. Audience: contributors working across the TypeScript, Kotlin, and Swift layers, plus library users integrating it into an app.

This file is a glossary, not a spec. For architectural decisions see `docs/adr/`. For implementation details see the per-platform `CLAUDE.md` files (`ios/CLAUDE.md`, `website/CLAUDE.md`) and `ios/ARCHITECTURE.md`.

## Language

### Top level

**AudioBrowser**:
The library, and the JS namespace through which its public API is reached. Owns the **Browser** and the **Player**.
*Avoid*: Module, SDK, AudioModule.

**Browser**:
The navigation subsystem within AudioBrowser, exposing a single tree that powers both in-app browsing and the browse views of External surfaces (Android Auto, CarPlay). Owns the **BrowseTree**, **Routes**, **Tabs**, and **Search**.
*Avoid*: Navigation, MediaBrowser (the Android platform class).

**Player**:
The playback subsystem within AudioBrowser. Receives Playable Tracks and streams them.
*Avoid*: Engine, AudioEngine.

### Navigation

**Path**:
A position in the **BrowseTree**, expressed as a slash-delimited string (e.g. `/albums/abbey-road`). Passed to `navigate(path)`; **Routes** match against Paths to resolve content. See "Flagged ambiguities" — distinct from the HTTP `RequestConfig.path`.
*Avoid*: URL, route, address (when referring to the tree position).

**Route**:
A binding from a path pattern to a **BrowserSource**. Patterns support `{param}`, `*`, and `**`; the most specific match wins.
*Avoid*: Endpoint, handler, mapping.

**Tab**:
A top-level navigation entry shown in the tab bar of the browser UI.
*Avoid*: Section, category.

**Search**:
The voice- and text-driven query subsystem. Receives structured `SearchParams` — one cross-platform shape normalized from both iOS (SiriKit `INMediaSearch`) and Android (`MEDIA_PLAY_FROM_SEARCH`) voice/text intents — and returns a `Track[]`. The per-platform parsers absorb the wire differences; the `SearchParams` they emit is identical. Within it, **`mode`** is the *container vertical* (what kind of result: station / podcast / song / …), orthogonal to the *filter* props (`genre` / `artist` / `album`); **`reference`** is the media-reference axis (`'my'` = the user's own collection, routed to Search; "currently playing" is resolved natively and never reaches Search).
*Avoid*: Query, lookup. For `mode`, avoid conflating the vertical with the filter props.

**BrowserSource**:
Anything that can produce children for a path — the value on the right-hand side of a **Route**, or of `browse:` / `tabs:` / `search:`. Comes in three shapes, all producing a `ResolvedTrack`: a static `ResolvedTrack` with its children declared inline, a callback that returns one, or a `TransformableRequestConfig` that points at a JSON `ResolvedTrack`-shaped endpoint.
*Avoid*: Provider, handler.

### Tree

**BrowseTree**:
The navigable tree of Tracks exposed by the Browser. Browsable Tracks have children; Playable-only Tracks do not.
*Avoid*: Content tree, media tree, hierarchy.

**Track**:
The universal content type in the BrowseTree. A single `Track` can be **Browsable**, **Playable**, or both, depending on which fields are set. The name is a misnomer carried for platform-alignment reasons — see "Flagged ambiguities".
*Avoid*: Item, Node, MediaItem (those names belong to the platform SDKs). In public prose, lowercase “entry” is acceptable as a loose UI noun when the concrete content kind is unknown, but **Entry** is not a domain type.

**Browsable**:
A Track that has a `url` and resolves to children when navigated into. A *shape* of Track, not a separate type.
*Avoid*: Folder, Container, Directory.

**Public prose note**:
In guides, use **Browsable** for the abstract shape: a Track with a `url` that opens a Path and resolves to children. Do not replace it with a universal noun such as Folder, Container, Directory, Node, or Item.

When referring to a concrete thing in the BrowseTree, prefer the domain noun the integrator or listener would recognize: tab, album, playlist, category, show, station, collection, etc. Use lowercase “entry” only as a loose prose fallback when the concrete kind is unknown; do not promote **Entry** to a glossary term.

Keep the layers distinct:

* A **Tab** is a top-level navigation entry.
* A **Path** is the slash-delimited address navigated to.
* A **Route** is the binding that resolves a Path.
* A **Browsable Track** is the Track shape with a `url`.
* The resolved children for a Path are its content, not the tab or route itself.

Example:

* Prefer: “A child with a `url` is browsable and opens another Path.”
* Prefer: “Re-fetch the `/favorites` content.”
* Avoid: “A child with a `url` is a folder.”
* Avoid: “Re-fetch the Favorites tab.”

**Playable**:
A Track that has a `src` and can be streamed by the player. A *shape* of Track, not a separate type. A Track can be both Browsable and Playable (e.g. a radio station with a schedule sub-tree).
*Avoid*: Leaf, Song, Media.

**ResolvedTrack**:
The return type of `navigate()` — a Track that has gone through the browse pipeline. Compared to the declared **Track** form an app/API supplies, a ResolvedTrack carries the transformed `artworkSource` (ready for `<Image>`), an optionally hydrated `favorited` flag, and — for Browsable Tracks — populated `children`. Media URLs are not part of resolution; they're transformed at playback time.
*Avoid*: ExpandedTrack, LoadedTrack.

### Requests

**Resolve**:
An optional per-Track callback that produces a RequestConfig from a Track's metadata. Used by the `media` and `artwork` request pipelines when the request needs Track fields (artist, album, src) to be built.
*Avoid*: Build, generate.

**Transform**:
The final step in any outbound request, applied to the merged RequestConfig just before it goes out. Used to sign URLs, attach auth tokens, or fold in size hints. Optional.
*Avoid*: Finalize, sign, decorate.

**Request-Config Layer**:
One config in the outbound-request stack, applied base-up: **request** (shared) → **kind** (browse / search / media) → **route** (per-Route). A Request-Config Layer with a **Transform** replaces the running config entirely (transform-wins); otherwise its static fields merge over it — except `path`, which is carried from the base (only a Transform may change it). `buildApiRequest` / `applyLayers` are the canonical application of the stack. Artwork configs are deliberately *not* applied as Request-Config Layers: their static fields merge override-wins, so a `nowPlayingArtwork` like `{ path: "/artwork/{id}" }` can supply the path.
*Avoid*: Layer (unqualified — too generic), stage, level.

### Playback

**Queue**:
The Player's working list of Tracks. Has a current index (the **Active Track**), a repeat mode, and a shuffle order.
*Avoid*: Playlist (a playlist is a Browser concept — user-curated content), playback list.

**Active Track**:
The Track at the Queue's current index. The Track that is (or would be) playing. Distinct from **Now Playing** — Active Track is queue state, Now Playing is display metadata.
*Avoid*: Current Track, Playing Track.

**Now Playing**:
The display metadata surfaced on lockscreen / notification / CarPlay / Android Auto. Mirrors the **Active Track**'s metadata by default; the app may override it via `updateNowPlaying()` (the live-stream use case: the station stays as the Active Track while Now Playing reflects the current song).
*Avoid*: Lockscreen info, Notification info, Media metadata.

### Playback state

Three closely-named concepts that sound interchangeable but are not.

**PlaybackState**:
The player's state machine label. One of six values: `idle`, `stopped`, `loading`, `playing`, `paused`, `error`. Low-level — UI code should usually consume `PlayingState` instead.
*Avoid*: PlayerState, EngineState.

**playWhenReady**:
Whether the user wants playback to start automatically when the Active Track has loaded and buffered. Calling `play()` sets it to `true`, `pause()` to `false`. Independent of `PlaybackState`.
*Avoid*: AutoPlay, ShouldPlay.

**PlayingState**:
The UX-level state: a struct of two booleans `{ playing, buffering }`. Derived from `PlaybackState` + `playWhenReady`. What a play/pause button and loading spinner should bind to.
*Avoid*: PlaybackStatus, PlayingFlags, UIState.

### Playback failure

**PlaybackErrorKind**:
The normalized, cross-platform classification of why playback failed. Each platform maps its own native failure onto this set (AVFoundation on iOS, ExoPlayer on Android, Shaka on web), so an app can branch on it without knowing which engine produced it. **The only part of a PlaybackError that may drive user-facing copy.** The parallel of `NavigationErrorType` on the browsing side.
*Avoid*: error type, error category, error reason.

**PlaybackError code**:
The raw native failure identifier carried alongside the Kind, for diagnostics and telemetry only. Deliberately *not* a contract: its values are the underlying engine's own, so they differ per platform and change with it. Never branch on it, never show it.
*Avoid*: treating it as an enum.

A failure carrying no evidence of a cause takes the catch-all Kind rather than being guessed into a more specific one. A wrong classification both misleads the listener and corrupts the telemetry aggregates the Kind exists to make possible.

**Load**:
One track's playback session: created when a track becomes current (selection, queue advance, skip) or restarted from a terminal error (`retry()`, or play while in `error`), surviving every *automatic* retry reload of that track. The unit that retry budgets, the **hasPlayed** flag, and advisory-error deduplication are scoped to. A new load starts fresh; retries within one don't.
*Avoid*: request, attempt (an attempt is one try *within* a load).

**Advisory (retrying) error**:
A classified PlaybackError surfaced *while automatic retry is still working on it* — `retrying: true`, attached via `onPlaybackChanged` to a non-terminal playback state, so UIs can show the cause over a spinner. Provisional by definition: it clears when playback recovers, or hardens into a terminal error (state `error`, `retrying` absent) when the retry budget runs out. Terminal errors alone fire `onPlaybackError`.
*Avoid*: warning, soft error, pending error.

**First-connect budget** / **Recovery budget**:
The two duration bounds on automatic retry, selected per load by whether it has ever rendered audio (**hasPlayed**): a short budget (default 12s, counting only a contiguous online stretch) for a load that never played, the long one (default 2 min) once playback has proven the source works. Durations bound the give-up promise; attempt counts only pace the backoff. See ADR 0004.
*Avoid*: retry limit, timeout (both suggest a single number).

### Metadata

**TrackMetadata**:
Static metadata extracted from a media file's container or tag frames (ID3, MP4 atoms, etc.) at load time. Distinct from a **Track**'s own app-provided fields, which share names like `title`, `artist`: TrackMetadata is what the *file says about itself*, Track fields are what the *app declared*.
*Avoid*: TrackInfo, MediaMetadata.

**TimedMetadata**:
Metadata streamed mid-playback — ICY frames from Shoutcast/Icecast or in-band ID3 frames from HLS. The library does not auto-apply it to **Now Playing**; the app forwards selected fields via `updateNowPlaying()`. This is the live-radio data flow.
*Avoid*: StreamMetadata, ID3Event.

### External surfaces

**External surface**:
Any non-app integration that the library drives or receives input from. Includes: iOS lockscreen / Control Center, Android notification, **CarPlay**, **Android Auto**, **Wear OS**, **AAOS** (Android Automotive), and Bluetooth / car head units. Surfaces vary in capability — some browse the tree, some only show Now Playing, some only send Remote commands.
*Avoid*: External controller, media controller (both are used in the codebase but conflate display-only, browse-capable, and input-only surfaces).

**Remote command**:
An input event from an External surface — play, pause, next, previous, seek, favorite, etc. Surfaced via `onRemote*` callbacks; optional `handleRemote*` callbacks let the app override default Player behaviour.
*Avoid*: Remote event, remote action.

**Capability**:
A flag controlling whether a specific control is *available* on External surfaces. Disabling a Capability hides the corresponding control and prevents the matching **Remote command** from firing. Distinct from Remote command: a Capability is what's *configured*, a Remote command is the *event* fired when an available Capability is invoked.
*Avoid*: Permission, Feature flag, Control.

**Remote button**:
A button an External surface draws, which emits a **Remote command** when tapped — skip, jump, favorite. Distinct from a **Capability**: a Capability decides whether the button may exist at all, a Remote button is the thing rendered. Android only; CarPlay's now-playing buttons are configured separately.
*Avoid*: Notification button, player button, control button.

**Remote button layout**:
The arrangement of **Remote buttons** on Android, published once and honoured by every Android External surface — notification, Android Auto, and the Android 13+ system media controls. Has exactly three positions: `back` and `forward` either side of play/pause, and `overflow` for the rest. A layout describes the whole arrangement; omitting it derives one from **Capabilities**.
*Avoid*: Notification buttons, slots (a Media3 implementation term — `back`/`forward`/`overflow` are the domain names).

**Favorited**:
A boolean on a Track marking it as a user favorite. Toggled programmatically or via the heart button on an External surface. The library's domain vocabulary has no Rating concept.

A favorites collection is app-owned/user-owned content. The library tracks **Favorited** state on Tracks and keeps surfaces in sync; the app owns where the favorites collection is stored, how it is persisted, and how a favorites Path resolves to Tracks.
*Avoid*: Rating, hearted, liked, starred.

**Browse Gate**:
An app-imposed block on browsing from External surfaces, set and cleared at runtime. While gated, **Tabs** stay visible but every tab's content is replaced by a single message — rendered as each surface allows (a full-page view on CarPlay, a list tile on Android Auto) — and **Search** from External surfaces resolves to the same message. The **Player**, the **Queue**, and **Now Playing** are unaffected: a gate blocks *finding* content, never *hearing* it. Generic by design — subscription, login, and region blocks are all Browse Gates.
*Avoid*: Paywall (one app's reason for a gate, not the concept), error page (a gate is deliberate app state, not a **NavigationError**), lock screen (that's an External surface).

## Relationships

* A **Browser** holds zero or more **Routes**, up to four **Tabs**, and optionally one **Search**.
* The **Browser** produces **ResolvedTracks**; the **Player** consumes their Playable **Tracks** via the **Queue**.
* A **Queue** holds zero or more **Tracks** and has at most one **Active Track**.
* A live stream emits **TimedMetadata**; the app may forward fields into the **Now Playing** override.
* The `media` and `artwork` request pipelines accept a per-Track **Resolve**; all requests accept a final **Transform**.
* **External surfaces** display **Now Playing**, may browse the **BrowseTree**, and emit **Remote commands**.
* A **Capability** controls whether a matching **Remote command** can be invoked from an External surface.
* A **Browse Gate** blocks the **BrowseTree** and **Search** on External surfaces, but never the **Player**, the **Queue**, or **Now Playing**.
* A Track is **Favorited** independently of being the Active Track — favoriting is set on the Track, not on the Queue.
* A favorites collection belongs to the app/user; **Favorited** is the per-Track state the library keeps synchronized across surfaces.

## Example dialogue

> **Contributor:** "My radio integration calls `updateNowPlaying({ title })` when stream metadata arrives, but the notification keeps flickering back to the station name."
>
> **Maintainer:** "**Now Playing** snaps back to mirror the **Active Track**'s metadata whenever the Active Track changes. For a stream, the Active Track is the station — don't replace it when metadata arrives; just keep calling `updateNowPlaying`. The two diverge by design: the Active Track is *what's in the Queue*, Now Playing is *what's currently being heard*."
>
> **Contributor:** "If I put a remote artwork URL in the override, does my **Transform** still sign it?"
>
> **Maintainer:** "No — a Now Playing override bypasses the request pipeline entirely. The **External surface** fetches the URL directly, so bake any auth into the URL or use a signed CDN."

## Flagged ambiguities

* **`src` vs `url` on a Track.** Both are string fields and easy to mix up. `url` is a *navigation* address — its presence makes the Track **Browsable**. `src` is a *media* identifier (usually an audio URL) — its presence makes the Track **Playable**. A Track can have both. When in doubt: ask "do I navigate into this or stream this?"

* **`id` is the Playable Track's identity when present.** External surfaces mark the "now playing" browse row by comparing identities (CarPlay's playing indicator; Android Auto's, via the Media3 mediaId). A consumer-loaded Track's `src` can differ textually from the browse row's for the same item (absolute vs relative, extra query params), so when both sides carry an `id` it *is* the identity, and `src`/`url` equality is only the fallback for consumers that don't assign ids.

* **"path" has two senses.** A tree address (`/albums/abbey-road`, the navigation primitive) and an HTTP path (`/api/v2/albums/123/tracks`, the `path` field on `RequestConfig`). They are never both fields on the same object, but the same string can appear in both roles when a Route forwards a tree path into an HTTP request. Disambiguate by context, not by renaming.

* **`buffering` is a `PlayingState` flag, not a `PlaybackState` value.** `PlaybackState` has `loading` (covering both initial load and mid-stream rebuffering); `PlayingState` exposes the derived `buffering` boolean for UI binding. "The player is buffering" is a UX statement, not a state-machine claim.

* **"Rating" is a platform concept, not a library concept.** Android (`MediaSession.setRatingType`) and iOS (`MPFeedbackCommand`) expose generalised Rating APIs supporting thumbs / stars / percentages / hearts. This library uses only the heart variant and surfaces it as **Favorited**. The `Rating` types in `src/features/rating.ts` exist as platform-bridge plumbing and are not part of the domain vocabulary.

* **Tabs and Search look like Routes internally.** At the native layer, `tabs:` and `search:` (and the top-level `browse:`) are flattened into the same route table as user-declared routes, using magic paths `__tabs__`, `__search__`, and `__default__`. This is an implementation detail — domain-wise a Tab is not a Route; it's a top-bar navigation entry with a 4-entry constraint, and Search is a voice/text query handler. Don't promote the magic paths to the public vocabulary.

* **"Track" is overloaded.** It names the universal content type in the BrowseTree, but in everyday English a track is a song. A Track called "Jazz" with no `src` and 12 children does not behave like a track. This is intentional — Android Auto's `MediaItem` and CarPlay's `CPListItem` both model the browse tree with a single polymorphic type, and aligning with that shape is cheaper than fighting it. Tracked in [#39](https://github.com/radio-garden/react-native-audio-browser/issues/39).
