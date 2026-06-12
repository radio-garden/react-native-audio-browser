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
A top-level navigation entry shown in the tab bar of the browser UI.
_Avoid_: Section, category.

**Search**:
The voice- and text-driven query subsystem. Receives structured `SearchParams` (derived from Android voice intents) and returns a `Track[]`.
_Avoid_: Query, lookup.

**BrowserSource**:
Anything that can produce children for a path — the value on the right-hand side of a **Route**, or of `browse:` / `tabs:` / `search:`. Comes in three shapes, all producing a `ResolvedTrack`: a static `ResolvedTrack` with its children declared inline, a callback that returns one, or a `TransformableRequestConfig` that points at a JSON `ResolvedTrack`-shaped endpoint.
_Avoid_: Provider, handler.

### Tree

**BrowseTree**:
The navigable tree of Tracks exposed by the Browser. Browsable Tracks have children; Playable Tracks are leaves.
_Avoid_: Content tree, media tree, hierarchy.

**Track**:
The universal node type in the media tree. A single `Track` can be **Browsable**, **Playable**, or both, depending on which fields are set. The name is a misnomer carried for platform-alignment reasons — see "Flagged ambiguities".
_Avoid_: Item, Node, MediaItem (those names belong to the platform SDKs).

**Browsable**:
A Track that has a `url` and resolves to children when navigated into. A *shape* of Track, not a separate type.
_Avoid_: Folder, Container, Directory.

**Playable**:
A Track that has a `src` and can be streamed by the player. A *shape* of Track, not a separate type. A Track can be both Browsable and Playable (e.g. a radio station with a schedule sub-tree).
_Avoid_: Leaf, Song, Media.

**ResolvedTrack**:
The return type of `navigate()` — a Track that has gone through the browse pipeline. Compared to the declared **Track** form an app/API supplies, a ResolvedTrack carries the transformed `artworkSource` (ready for `<Image>`), an optionally hydrated `favorited` flag, and — for Browsable Tracks — populated `children`. Media URLs are not part of resolution; they're transformed at playback time.
_Avoid_: ExpandedTrack, LoadedTrack.

### Requests

**Resolve**:
An optional per-Track callback that produces a RequestConfig from a Track's metadata. Used by the `media` and `artwork` request pipelines when the request needs Track fields (artist, album, src) to be built.
_Avoid_: Build, generate.

**Transform**:
The final step in any outbound request, applied to the merged RequestConfig just before it goes out. Used to sign URLs, attach auth tokens, or fold in size hints. Optional.
_Avoid_: Finalize, sign, decorate.

**Request-Config Layer**:
One config in the outbound-request stack, applied base-up: **request** (shared) → **kind** (browse / search / media) → **route** (per-Route). A Request-Config Layer with a **Transform** replaces the running config entirely (transform-wins); otherwise its static fields merge over it — except `path`, which is carried from the base (only a Transform may change it). `buildApiRequest` / `applyLayers` are the canonical application of the stack. Artwork configs are deliberately *not* applied as Request-Config Layers: their static fields merge override-wins, so a `nowPlayingArtwork` like `{ path: "/artwork/{id}" }` can supply the path.
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

### Metadata

**TrackMetadata**:
Static metadata extracted from a media file's container or tag frames (ID3, MP4 atoms, etc.) at load time. Distinct from a **Track**'s own app-provided fields, which share names like `title`, `artist`: TrackMetadata is what the *file says about itself*, Track fields are what the *app declared*.
_Avoid_: TrackInfo, MediaMetadata.

**TimedMetadata**:
Metadata streamed mid-playback — ICY frames from Shoutcast/Icecast or in-band ID3 frames from HLS. The library does not auto-apply it to **Now Playing**; the app forwards selected fields via `updateNowPlaying()`. This is the live-radio data flow.
_Avoid_: StreamMetadata, ID3Event.

### External surfaces

**External surface**:
Any non-app integration that the library drives or receives input from. Includes: iOS lockscreen / Control Center, Android notification, **CarPlay**, **Android Auto**, **Wear OS**, **AAOS** (Android Automotive), and Bluetooth / car head units. Surfaces vary in capability — some browse the tree, some only show Now Playing, some only send Remote commands.
_Avoid_: External controller, media controller (both are used in the codebase but conflate display-only, browse-capable, and input-only surfaces).

**Remote command**:
An input event from an External surface — play, pause, next, previous, seek, favorite, etc. Surfaced via `onRemote*` callbacks; optional `handleRemote*` callbacks let the app override default Player behaviour.
_Avoid_: Remote event, remote action.

**Capability**:
A flag controlling whether a specific control is *available* on External surfaces. Disabling a Capability hides the corresponding control and prevents the matching **Remote command** from firing. Distinct from Remote command: a Capability is what's *configured*, a Remote command is the *event* fired when an available Capability is invoked.
_Avoid_: Permission, Feature flag, Control.

**Favorited**:
A boolean on a Track marking it as a user favorite. Toggled programmatically or via the heart button on an External surface. The library's domain vocabulary has no Rating concept.
_Avoid_: Rating, hearted, liked, starred.

**Browse Gate**:
An app-imposed block on browsing from External surfaces, set and cleared at runtime. While gated, **Tabs** stay visible but every tab's content is replaced by a single message — rendered as each surface allows (a full-page view on CarPlay, a list tile on Android Auto) — and **Search** from External surfaces resolves to the same message. The **Player**, the **Queue**, and **Now Playing** are unaffected: a gate blocks *finding* content, never *hearing* it. Generic by design — subscription, login, and region blocks are all Browse Gates.
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

## Example dialogue

> **Contributor:** "My radio integration calls `updateNowPlaying({ title })` when stream metadata arrives, but the notification keeps flickering back to the station name."
>
> **Maintainer:** "**Now Playing** snaps back to mirror the **Active Track**'s metadata whenever the Active Track changes. For a stream, the Active Track is the station — don't replace it when metadata arrives; just keep calling `updateNowPlaying`. The two diverge by design: the Active Track is *what's in the Queue*, Now Playing is *what's currently being heard*."
>
> **Contributor:** "If I put a remote artwork URL in the override, does my **Transform** still sign it?"
>
> **Maintainer:** "No — a Now Playing override bypasses the request pipeline entirely. The **External surface** fetches the URL directly, so bake any auth into the URL or use a signed CDN."

## Flagged ambiguities

- **`src` vs `url` on a Track.** Both are string fields and easy to mix up. `url` is a *navigation* address — its presence makes the Track **Browsable**. `src` is a *media* identifier (usually an audio URL) — its presence makes the Track **Playable**. A Track can have both. When in doubt: ask "do I navigate into this or stream this?"

- **"path" has two senses.** A tree address (`/albums/abbey-road`, the navigation primitive) and an HTTP path (`/api/v2/albums/123/tracks`, the `path` field on `RequestConfig`). They are never both fields on the same object, but the same string can appear in both roles when a Route forwards a tree path into an HTTP request. Disambiguate by context, not by renaming.

- **`buffering` is a `PlayingState` flag, not a `PlaybackState` value.** `PlaybackState` has `loading` (covering both initial load and mid-stream rebuffering); `PlayingState` exposes the derived `buffering` boolean for UI binding. "The player is buffering" is a UX statement, not a state-machine claim.

- **"Rating" is a platform concept, not a library concept.** Android (`MediaSession.setRatingType`) and iOS (`MPFeedbackCommand`) expose generalised Rating APIs supporting thumbs / stars / percentages / hearts. This library uses only the heart variant and surfaces it as **Favorited**. The `Rating` types in `src/features/rating.ts` exist as platform-bridge plumbing and are not part of the domain vocabulary.

- **Tabs and Search look like Routes internally.** At the native layer, `tabs:` and `search:` (and the top-level `browse:`) are flattened into the same route table as user-declared routes, using magic paths `__tabs__`, `__search__`, and `__default__`. This is an implementation detail — domain-wise a Tab is not a Route; it's a top-bar navigation entry with a 4-entry constraint, and Search is a voice/text query handler. Don't promote the magic paths to the public vocabulary.

- **"Track" is overloaded.** It names the universal tree node, but in everyday English a track is a song. A Track called "Jazz" with no `src` and 12 children does not behave like a track. This is intentional — Android Auto's `MediaItem` and CarPlay's `CPListItem` both model the browse tree with a single polymorphic node, and aligning with that shape is cheaper than fighting it. Tracked in [#39](https://github.com/radio-garden/react-native-audio-browser/issues/39).
