# react-native-audio-browser

Domain glossary for the library. Audience: contributors working across the TypeScript, Kotlin, and Swift layers, plus library users integrating it into an app.

This file is a glossary, not a spec. For architectural decisions see `docs/adr/`. For implementation details see the per-platform `CLAUDE.md` files (`ios/CLAUDE.md`, `docs/CLAUDE.md`) and `ios/ARCHITECTURE.md`.

## Language

### Top level

**AudioBrowser**:
The library itself, and the JS singleton namespace through which all public API is reached (`AudioBrowser.navigate`, `AudioBrowser.configureBrowser`, `AudioBrowser.play`, …). On the native side this is the single Nitro entry point: `HybridAudioBrowser` (iOS) and the matching `AudioBrowser` class (Android). Owns the **Browser** and the **Player**.
_Avoid_: Module, SDK, AudioModule.

**Browser**:
The navigation subsystem within AudioBrowser. Owns the **BrowseTree**, **Routes**, **Tabs**, and **Search**. Configured via `configureBrowser`. Distinct from **AudioBrowser** (the whole library): the Browser is one of its two halves, the **Player** is the other.
_Avoid_: Navigation, MediaBrowser (that's the Android platform class).

**Player**:
The playback subsystem within AudioBrowser. Owns the **Queue**, current **PlaybackState**, **NowPlayingInfo**, **RemoteCommands**, **SleepTimer**, **Equalizer**. Receives Playable Tracks (selected from the Browser) and streams them. Implemented natively as `TrackPlayer` (iOS) and the Media3-based `Player` (Android).
_Avoid_: Engine, AudioEngine.

**browse** (verb):
To fetch the children of a path in the BrowseTree. Invoked via `navigate(path)`; resolves by matching the path against configured **Routes** and calling the matching **BrowserSource**.
_Avoid_: Fetch, load, resolve (these have other specific meanings — see MediaLoader, resolve callback).

### Tree

**BrowseTree**:
The navigable tree of Tracks exposed by the Browser. Browsable Tracks have children; Playable Tracks are leaves. Its shape is defined by the active Routes and the data each Route's BrowserSource returns.
_Avoid_: Content tree, media tree, hierarchy.

**Track**:
The universal node type in the media tree. A single `Track` can be **Browsable**, **Playable**, or both, depending on which fields are set. See "Flagged ambiguities" — the name is a misnomer carried for platform-alignment reasons, not because every Track is a song.
_Avoid_: Item, Node, MediaItem (when referring to this library's type — those names belong to the platform SDKs).

**Browsable**:
A Track that has a `url` and resolves to children when navigated into. Examples: a tab, an album, a genre, "Favorites". A Browsable is a *shape* of Track, not a separate type.
_Avoid_: Folder, Container, Directory.

**Playable**:
A Track that has a `src` and can be streamed by the player. Examples: a song, a radio station. A Playable is a *shape* of Track, not a separate type. A Track can be both Browsable and Playable (e.g. a radio station that also has a schedule sub-tree).
_Avoid_: Leaf, Song, Media.

**ResolvedTrack**:
A Browsable Track that has been navigated into and now carries its immediate `children`. The return type of `navigate()`. Always has a `url`; children is undefined for leaves.
_Avoid_: ExpandedTrack, LoadedTrack.

### Navigation

**Path**:
A position in the **BrowseTree**, expressed as a slash-delimited string (e.g. `/albums/abbey-road`). The argument to `navigate(path)`, the value of `getPath()` / `usePath()`, and the key against which **Routes** are matched. Distinct from `RequestConfig.path`, which is the HTTP path of an outbound request — these never co-occur in the same signature but a contributor reading code should know which sense applies. See "Flagged ambiguities".
_Avoid_: URL, route, address (when referring to the tree position).

**Route**:
A binding from a path pattern to a **BrowserSource**, declared in `BrowserConfiguration.routes`. When `navigate(path)` is called, the **Router** (`SimpleRouter`) picks the most specific matching pattern and invokes its source. Patterns support `{param}`, `*`, and `**` wildcards.
_Avoid_: Endpoint, handler, mapping.

**Tab**:
A top-level navigation entry shown in the tab bar of the browser UI (in-app, Android Auto, and CarPlay). Defined by `BrowserConfiguration.tabs` as a `Track[]`, callback, or HTTP source. Maximum 4 — a constraint imposed by Android Auto and CarPlay. The first Tab is auto-loaded on browser start.
_Avoid_: Section, category.

**Search**:
The voice- and text-driven search subsystem. Configured via `BrowserConfiguration.search`. Receives `SearchParams` — a structured shape derived from Android voice intents (`mode`, `query`, plus optional `artist`, `album`, `genre`, `title`, `playlist`) — and returns a `Track[]`.
_Avoid_: Query, lookup.

**BrowserSource**:
Anything that can produce children for a path. The value on the right-hand side of a **Route**, or of `browse:` / `tabs:` / `search:`. Comes in three shapes: **Static browser source**, **Callback browser source**, **Endpoint browser source**.
_Avoid_: Provider, handler, Source (bare — collides with `track.src` and other Source-y things).

**Static browser source**:
A `BrowserSource` whose value is a literal `ResolvedTrack` (or `Track[]` for tabs). Content known at configuration time. No network, no callback.
_Avoid_: Inline, hardcoded.

**Callback browser source**:
A `BrowserSource` whose value is a function (`BrowserSourceCallback`, `SearchSourceCallback`, `TabsSourceCallback`). Called on demand with a `path` and optional `routeParams`. Returns a `Promise<BrowseResult>`.
_Avoid_: Function source, lambda source.

**Endpoint browser source**:
A `BrowserSource` whose value is a `TransformableRequestConfig` — declarative HTTP description (baseUrl, headers, query, transform). The library does the fetch and JSON decoding.
_Avoid_: HTTP source, API source, URL source.

**RouteConfig**:
The extended form of a **Route**'s value — a `{ browse, media, artwork }` object — used when a route needs per-route overrides for **media** or **artwork** request config in addition to the BrowserSource. Plain `BrowserSource` values are auto-promoted to a `RouteConfig` internally.
_Avoid_: Route, RouteEntry (those are different things).

### Requests

**Content request**:
An outbound request whose response describes tree items. The library decodes the response into Tracks. The two Content requests are **browse** and **search**.
_Avoid_: Data request, API request.

**Asset request**:
An outbound request whose response is bytes consumed by a platform player. The library does not decode the response — the platform (AVPlayer / ExoPlayer / image loader) streams it. The two Asset requests are **media** and **artwork**.
_Avoid_: Binary request, stream request.

**media**:
The Asset request for an audio stream. Sourced from a Track's `src`.
_Avoid_: Audio, stream.

**artwork**:
The Asset request for an image. Sourced from a Track's `artwork`. May carry an **ImageContext** with display-size hints from CarPlay / Android Auto.
_Avoid_: Image, cover.

**ImageContext**:
The size hint (width, height in pixels) attached to an artwork request when the consuming surface — CarPlay, Android Auto, Now Playing — knows the display dimensions. Absent at browse time, when display size is unknown.
_Avoid_: SizeHint, DisplayContext.

**Resolve**:
The per-Track step in an Asset request that turns a Track into a RequestConfig. Takes a Track, returns the request to make for that Track. Optional — used when the request needs Track metadata (artist, album, src) to be built.
_Avoid_: Build, generate.

**Transform**:
The final step in any request, applied to the merged RequestConfig just before it goes out. Used to sign URLs, attach auth tokens, or fold in `ImageContext` size hints. Optional.
_Avoid_: Finalize, sign, decorate.

### Playback

**Queue**:
The Player's working list of Tracks. Has a current index (the **Active Track**), a **repeat mode**, and a **shuffle order**. Populated from the Browser when a Track is played from a context (album, playlist, search results), or set explicitly via the queue API.
_Avoid_: Playlist (a playlist is a Browser concept — user-curated content), playback list.

**Active Track**:
The Track at the Queue's current index. The Track that is (or would be) playing. Changes when the Queue advances, the user skips, or the active index is set explicitly. Distinct from **Now Playing** — Active Track is queue state, Now Playing is display metadata.
_Avoid_: Current Track, Playing Track.

**Now Playing**:
The display metadata shown on the lockscreen, notification, CarPlay Now Playing screen, and Android Auto Now Playing surface. Mirrors the Active Track's metadata by default, but can be overridden via `updateNowPlaying()` to display different title / artist / artwork without changing the Active Track. The override is the live-stream use case: the Active Track stays as the station, while Now Playing reflects the current song.
_Avoid_: Lockscreen info, Notification info, Media metadata.

### Playback state

Three closely-named concepts that sound interchangeable but are not.

**PlaybackState**:
The engine's state machine label. One of nine values: `none`, `ready`, `playing`, `paused`, `stopped`, `loading`, `buffering`, `error`, `ended`. Returned (with the last error) inside `Playback`. Low-level — UI code should usually consume `PlayingState` instead.
_Avoid_: PlayerState, EngineState.

**playWhenReady**:
Whether the user wants playback to start automatically when possible. Calling `play()` sets it to `true`, `pause()` to `false`. Independent of `PlaybackState` — `playWhenReady = true` while buffering means "will play once buffering finishes".
_Avoid_: AutoPlay, ShouldPlay.

**PlayingState**:
The UX-level state: a struct of two booleans `{ playing, buffering }`. Derived from `PlaybackState` + `playWhenReady`. What a play/pause button and loading spinner should bind to.
_Avoid_: PlaybackStatus, PlayingFlags, UIState.

### Metadata

**TrackMetadata**:
Static metadata extracted from a media file's container or tag frames (ID3, MP4 atoms, etc.) at load time. Surfaced via `onTrackMetadata`. Distinct from a **Track**'s own app-provided fields (which share names like `title`, `artist`): TrackMetadata is what the *file says about itself*, Track fields are what the *app declared*.
_Avoid_: TrackInfo, MediaMetadata.

**TimedMetadata**:
Metadata streamed mid-playback — ICY frames from Shoutcast/Icecast or in-band ID3 frames from HLS. Fires through `onTimedMetadata` as new data arrives. The library does not auto-apply it to **Now Playing**; the app forwards selected fields via `updateNowPlaying()`. This is the live-radio data flow.
_Avoid_: StreamMetadata, ID3Event.

**ChapterMetadata**:
A chapter boundary parsed from the media file — start time, end time, title, optional URL. Used in podcasts and audiobooks. Fires through `onChapterMetadata` with the full chapter list when discovered.
_Avoid_: Chapter, ChapterInfo.

### External surfaces

**External surface**:
Any non-app integration that the library drives or receives input from. Includes: iOS lockscreen / Control Center, Android notification, **CarPlay**, **Android Auto**, **Wear OS**, **AAOS** (Android Automotive), and Bluetooth / car head units. Surfaces vary in capability — some browse the tree, some only show Now Playing, some only send Remote commands.
_Avoid_: External controller, media controller (both are used in the codebase but conflate display-only, browse-capable, and input-only surfaces).

**Remote command**:
An input event from an External surface — play, pause, next, previous, seek, favorite, etc. Defined as the `RemoteCommand` enum and surfaced via the `onRemote*` callbacks. Optional `handleRemote*` callbacks let the app override default Player behaviour for a command.
_Avoid_: Remote event, remote action.

**Capability**:
A flag controlling whether a specific control is *available* to the user on External surfaces. Fields on `PlayerCapabilities` — `play`, `pause`, `seekTo`, `skipToNext`, `favorite`, `shuffleMode`, `jumpForward`, etc. Disabling a Capability hides the corresponding control on notification / lockscreen / CarPlay / Android Auto, and prevents the matching Remote command from firing. Distinct from **Remote command**: a Capability is what's *configured*, a Remote command is the *event* fired when an available Capability is invoked.
_Avoid_: Permission, Feature flag, Control.

### Favorites

**Favorited**:
A boolean flag marking a Track as a user favorite. Set programmatically via `setActiveTrackFavorited()` / `toggleActiveTrackFavorited()`, or by the heart button on an External surface (notification, CarPlay, Android Auto). Persistence belongs to the app — `setFavorites(srcs)` hydrates the native cache on launch. The library's domain vocabulary has no Rating concept.
_Avoid_: Rating, hearted, liked, starred.

### Naming conventions

Conventions used in code that carry real semantic signal. Listed here so future contributors don't muddle them.

**`*Source`**:
Has two distinct motivations in this codebase. Don't introduce a third without flagging it.
1. **Polymorphic content provider** (library convention): a value that produces children for a slot, in one of three shapes — Static, Callback, Endpoint. Examples: `BrowserSource`, `RouteSource`, `TabsSource`, `SearchSource`.
2. **React Native `<Image source>` prop shape** (RN convention): an output value shaped to be passed directly into `<Image source={...}>`. Example: `ImageSource` (populated as `track.artworkSource`).

## Relationships

- An **AudioBrowser** owns one **Browser** and one **Player**.
- A **Browser** holds zero or more **Routes**, up to four **Tabs**, and optionally one **Search**.
- A **Route** binds a path pattern to one **BrowserSource**.
- A **BrowserSource** is exactly one of: **Static**, **Callback**, or **Endpoint browser source**.
- A **Path** addresses a node in the **BrowseTree**; **Routes** match against Paths.
- The **Browser** produces **Tracks**; the **Player** consumes them via the **Queue**.
- A **Queue** holds zero or more **Tracks** and has at most one **Active Track**.
- **Now Playing** mirrors the **Active Track**'s metadata by default; the app may override it.
- **PlayingState** is derived from **PlaybackState** + **playWhenReady**.
- A live stream emits **TimedMetadata**; the app may forward fields into the **Now Playing** override.
- **Asset requests** (media, artwork) accept a per-Track **Resolve**; all requests accept a final **Transform**.
- **External surfaces** display **Now Playing**, may browse the **BrowseTree**, and emit **Remote commands**.
- A **Capability** controls whether a matching **Remote command** can be invoked from an External surface.
- A Track is **Favorited** independently of being the Active Track; favoriting is set on the Track, not on the Queue.

## Example dialogue

> **Contributor:** "My radio integration calls `updateNowPlaying({ title })` when stream metadata arrives, but the notification keeps flickering back to the station name."
>
> **Maintainer:** "**Now Playing** snaps back to mirror the **Active Track**'s metadata whenever the Active Track changes. For a stream, the Active Track is the station — don't replace it when metadata arrives; just keep calling `updateNowPlaying`. The two diverge by design: the Active Track is *what's in the Queue*, Now Playing is *what's currently being heard*."
>
> **Contributor:** "Does the artwork in the override go through the **artwork** **Transform**?"
>
> **Maintainer:** "No. A Now Playing override isn't an **Asset request** — the **External surface** fetches the URL directly. Bake the auth into the URL or use a signed CDN."

## Flagged ambiguities

- **`loading` and `buffering` are not synonyms.** Both are `PlaybackState` values. `loading` is the *initial* load when a track starts; `buffering` is *mid-playback* re-buffering after the stream stalls. A track passes through `loading` once at start, then enters `playing` / `paused`, and may transition to `buffering` repeatedly during its lifetime. Reporting "the stream is loading" mid-playback is a category error.

- **"path" has two senses.** A tree address (`/albums/abbey-road`, the navigation primitive) and an HTTP path (`/api/v2/albums/123/tracks`, the `path` field on `RequestConfig`). They are never both fields on the same object, but the same string can appear in both roles when a Route forwards a tree path into an HTTP request. Disambiguate by context, not by renaming.

- **"Rating" is a platform concept, not a library concept.** Android (`MediaSession.setRatingType`) and iOS (`MPFeedbackCommand`) expose generalised Rating APIs supporting thumbs / stars / percentages / hearts. This library uses only the heart variant and surfaces it as **Favorited**. The `Rating` types in `src/features/rating.ts` exist as platform-bridge plumbing and are not part of the domain vocabulary.

- **Tabs and Search look like Routes internally.** At the native layer, `tabs:` and `search:` (and the top-level `browse:`) are flattened into the same route table as user-declared routes, using magic paths `__tabs__`, `__search__`, and `__default__`. This is an implementation detail — domain-wise a Tab is not a Route; it's a top-bar navigation entry with a 4-entry constraint, and Search is a voice/text query handler. Don't promote the magic paths to the public vocabulary.

- **"Track" is overloaded.** It names the universal tree node, but in everyday English a track is a song. A Track called "Jazz" with no `src` and 12 children does not behave like a track. This is intentional — Android Auto's `MediaItem` and CarPlay's `CPListItem` both model the browse tree with a single polymorphic node, and aligning with that shape is cheaper than fighting it. The cost is paid in documentation (this entry) rather than refactoring. Tracked in [#39](https://github.com/radio-garden/react-native-audio-browser/issues/39).
