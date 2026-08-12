# Enrich the Play Funnel: `reference` Axis + `SearchMode` Verticals — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface the iOS Siri media-reference axis (`reference: 'my' | 'unknown'`) to the consumer's `search` source, and redefine `SearchMode` as the result-kind "container vertical" (dropping the redundant `genre`/`artist`/`album` values, adding `station`/`podcast`/`audiobook`/`news`/`music` + the granular video kinds).

**Architecture:** Both platforms already normalize their native voice intents (`INMediaSearch` on iOS, `MEDIA_PLAY_FROM_SEARCH` on Android) into one cross-platform Nitro `SearchParams` struct, consumed by the `search` source (`SearchSourceCallback = (params) => Promise<Track[]>`). This plan changes that shared struct: it adds a `reference` field and reshapes `SearchMode`. Because `SearchParams`/`SearchMode` are Nitro types, the TS change drives codegen, then each native surface (iOS Swift, Android Kotlin) is updated to match the regenerated bindings. `reference: '.currentlyPlaying'` is resolved natively (resume) and never reaches the consumer; only `'my'` and `'unknown'` cross the bridge.

**Tech Stack:** TypeScript (Nitro spec types), Swift (iOS, `swift test` for pure units), Kotlin (Android), Nitrogen codegen, Yarn 4 (`corepack yarn`), Swift Testing framework.

## Global Constraints

- **`corepack yarn`, never bare `yarn`** — the library pins Yarn 4; global `yarn` is v1 and errors.
- **`SearchMode` final value set (verbatim):** `'any' | 'song' | 'playlist' | 'station' | 'podcast' | 'audiobook' | 'news' | 'music' | 'music-video' | 'movie' | 'tv-show' | 'tv-show-episode'`. Dropped vs today: `'genre'`, `'artist'`, `'album'`. The video kinds stay granular (not collapsed to a single `'video'`) so consumers can special-case them; unhandled ones free-fall to the consumer's default. `mode` stays **optional** (`mode?`) — absence means unstructured/unclassified; `'any'` keeps its "play anything good / smart shuffle, empty query" meaning.
- **`reference` is REQUIRED on `SearchParams`** with values `'my' | 'unknown'` (type `MediaReference`). Android always emits `'unknown'`. Adding a required field breaks every `SearchParams(...)` constructor — all sites must be updated (enumerated in Task 1/3/4).
- **`mode` is the container vertical only** (what _kind_ of result); `genre`/`artist`/`album`/`title`/`playlist` are _filter_ props that ride alongside and are read directly by the consumer. `mode` is sourced **solely from `mediaType`** (iOS) / focus (Android) — never derived from which filter prop is set.
- **`.currentlyPlaying` is native-only:** it routes to the existing resume branch (warm play / cold restore / fail) and is NEVER placed on `SearchParams`. Only `.my` → `SearchParams.reference = 'my'`.
- **Nitro coupling:** changing `SearchMode`/`SearchParams` requires `corepack yarn codegen` (regenerates `nitrogen/generated/` + rebuilds `lib/`) and, in the consuming app, `pod install`. `codegen` runs `tsc` over `src/` **including the web stub** (`src/web/`) — the web stub must compile. Swift/Kotlin are NOT compiled by codegen; native errors surface only at build. **Consequence:** after Task 1 the native build is temporarily red (native still references old `SearchMode.GENRE` etc.); it goes green again at Task 4. Each task is verified by its OWN signal (codegen+tsc / `swift test` / iOS build / Android build), not the full app build until Task 5.
- **iOS `MediaIntentCriteria` stays `Intents`-free** (pure, in the `AudioBrowserTestable` target) so it's unit-testable via `swift test`. All `INMediaSearch`/`INMediaItemType`/`INMediaReference` mapping lives in `RNABMediaIntentHandler` (the `Intents`-aware layer), which passes plain `String`/enum values into the criteria.
- **Running iOS Swift tests:** `swift test --disable-sandbox` (sandbox off). Ignore the pre-existing `PlaybackStateMachineTests` failures — they are unrelated.

## The `INMediaItemType` → `SearchMode` collapse table (canonical; used in Task 3)

| `INMediaItemType`                                                    | → `SearchMode` string                    |
| -------------------------------------------------------------------- | ---------------------------------------- |
| `station`, `radioStation`, `algorithmicRadioStation`, `musicStation` | `"station"`                              |
| `podcastShow`, `podcastEpisode`, `podcastPlaylist`, `podcastStation` | `"podcast"`                              |
| `audioBook`                                                          | `"audiobook"`                            |
| `news`                                                               | `"news"`                                 |
| `music`                                                              | `"music"`                                |
| `song`                                                               | `"song"`                                 |
| `playlist`                                                           | `"playlist"`                             |
| `musicVideo`                                                         | `"music-video"`                          |
| `movie`                                                              | `"movie"`                                |
| `tvShow`                                                             | `"tv-show"`                              |
| `tvShowEpisode`                                                      | `"tv-show-episode"`                      |
| `album`, `artist`, `genre`, `unknown`, (any other)                   | `nil` (filter or unclassified → no mode) |

---

## File Structure

- `src/types/browser.ts` — `SearchMode` (reshape), `MediaReference` (new type), `SearchParams.reference` (new required field) + doc updates. **Source of truth; drives codegen.**
- `src/web/NativeAudioBrowser.ts` — one `SearchParams` wrap site (`:506`) gains `reference`.
- `nitrogen/generated/**` — regenerated by codegen (do not hand-edit).
- `ios/Player/MediaIntentCriteria.swift` — `Reference` enum replaces `hasReference: Bool`; `isResume` becomes a 3-way switch; `searchMode` becomes `mediaTypeMode` pass-through (no field-derivation).
- `ios/Tests/MediaIntentCriteriaTests.swift` — rewritten expectations for the new `reference` + `mode` behavior.
- `ios/CarPlay/RNABMediaIntentHandler.swift` — maps `INMediaReference` → `Reference`, `INMediaItemType` → collapse-table string.
- `ios/HybridAudioBrowser.swift` — funnel (`:1637`) assembles `reference` into `SearchParams`; `BrowserManager.swift:627` text-search constructor gains `reference`.
- `android/src/main/java/com/audiobrowser/Service.kt` — `parseSearchIntent` mode trim + `reference = MediaReference.UNKNOWN`.
- `android/src/main/java/com/audiobrowser/browser/BrowserManager.kt` — `SearchParams(...)` sites (`:721`, `:777`) gain `reference`; optional `reference` serialization (`:1247`).

---

## Task 1: Reshape the TypeScript types + web stub, run codegen

**Files:**

- Modify: `src/types/browser.ts:60-112` (SearchMode doc + enum; SearchParams + reference)
- Modify: `src/web/NativeAudioBrowser.ts:506` (SearchParams wrap)
- Test: `corepack yarn codegen` (tsc over `src/` + web stub) + `npx tsc --noEmit`

**Interfaces:**

- Produces: `SearchMode` (new 12-value union, no genre/artist/album), `MediaReference = 'my' | 'unknown'`, `SearchParams.reference: MediaReference` (required), `SearchParams.mode?: SearchMode` (still optional). Native tasks consume the regenerated `nitrogen/generated/**/SearchMode.{swift,kt}` and `MediaReference.{swift,kt}` enums and the `reference` field on the generated `SearchParams`.

- [ ] **Step 1: Rewrite the `SearchMode` type + doc**

Replace `src/types/browser.ts:60-80` (the doc block and `export type SearchMode`) with:

```ts
/**
 * Search mode — the *container vertical*: what KIND of result the user asked
 * for. Orthogonal to the filter props (`genre`/`artist`/`album`/`title`/
 * `playlist`), which say *which* item. `mode` is optional: when absent, the
 * request is unstructured (text-search `query`) or unclassified.
 *
 * - `any`: play anything sensible — "play something" / smart shuffle (query
 *   empty). Android also maps its generic "play music" focus here, since it
 *   can't isolate the music vertical the way iOS can.
 * - `song`: an individual track
 * - `playlist`: a named playlist / mix
 * - `station`: a live radio station / channel
 * - `podcast`: a podcast (series, episode, or station)
 * - `audiobook`: an audiobook
 * - `news`: news content
 * - `music`: the music vertical, as opposed to talk/podcasts/audiobooks
 *   ("play music" on iOS, via the music media type)
 * - `music-video` / `movie` / `tv-show` / `tv-show-episode`: video kinds
 *   (an audio app cannot play these; surfaced so consumers may special-case —
 *   ignoring them degrades to an unstructured search)
 *
 * NOTE: there is intentionally no `genre`/`artist`/`album` member — those are
 * filters, not result shapes. Read them from `SearchParams.genre`/`.artist`/
 * `.album` directly.
 *
 * @see BrowserConfiguration.search
 * @see SearchParams
 */
export type SearchMode =
  | 'any'
  | 'song'
  | 'playlist'
  | 'station'
  | 'podcast'
  | 'audiobook'
  | 'news'
  | 'music'
  | 'music-video'
  | 'movie'
  | 'tv-show'
  | 'tv-show-episode'

/**
 * The media-reference axis from a voice intent.
 *
 * - `my`: the user's own collection ("play my favorites") — routed to the
 *   `search` source so the consumer resolves it against their library.
 * - `unknown`: no reference (the default; Android always emits this).
 *
 * NOTE: "currently playing" ("play this") is resolved natively (resume) and
 * never reaches the consumer, so it is not a value here.
 */
export type MediaReference = 'my' | 'unknown'
```

- [ ] **Step 2: Add `reference` to `SearchParams` and update its doc**

Replace the `SearchParams` interface doc + body at `src/types/browser.ts:82-112` with:

```ts
/**
 * Structured search parameters normalized from a voice/search intent — one
 * cross-platform shape (iOS SiriKit + Android MediaSession). `mode` is the
 * container vertical; the remaining fields are filters. Example mappings:
 * - "play something"            → mode='any', query="" (smart shuffle)
 * - "play music"                → mode='music' (iOS) / 'any' (Android), query=""
 * - "play jazz"                 → genre="jazz", query="jazz" (mode undefined)
 * - "play michael jackson"      → artist="michael jackson", query="michael jackson"
 * - "play thriller by m. jackson" → album="thriller", artist="michael jackson"
 * - "play billie jean"          → mode='song', title="billie jean", query="billie jean"
 * - "play my favorites"         → reference='my', query=""
 * - "play a jazz podcast"       → mode='podcast', genre="jazz"
 */
export interface SearchParams {
  /** Container vertical, or undefined for an unstructured / unclassified search. */
  mode?: SearchMode
  /**
   * The original search query string (always present, but may be empty string "").
   * With mode='any' and empty query, return any content the user would like
   * (e.g., recently played, favorites, or smart shuffle).
   */
  query: string
  /** Genre filter, when the intent named one. */
  genre?: string
  /** Artist filter (artist / album / song intents). */
  artist?: string
  /** Album filter. */
  album?: string
  /** Track title, for a song intent. */
  title?: string
  /** Playlist name, for a playlist intent. */
  playlist?: string
  /**
   * Media-reference axis. `'my'` = resolve against the user's own collection
   * ("play my favorites"); `'unknown'` = no reference (the default). Android
   * always emits `'unknown'`.
   */
  reference: MediaReference
}
```

- [ ] **Step 3: Fix the web stub's `SearchParams` construction**

`src/web/NativeAudioBrowser.ts:506` wraps a query string into `SearchParams` (the `search(query)` overload). It must now set the required `reference`. Find the object literal that builds `SearchParams` there (around line 506) and add `reference: 'unknown'` to it. For example, a wrap that currently reads `{ query, mode: undefined }`-style becomes:

```ts
// Wrap query string in SearchParams (matches Android's search(query: String) overload)
const params: SearchParams = { query, reference: 'unknown' }
```

(Keep any existing fields; only add `reference: 'unknown'`. `mode` and the filters stay absent for a bare text search.)

- [ ] **Step 4: Confirm the web `SearchManager` needs no logic change**

Read `src/web/browser/SearchManager.ts`. Its `search(params)` passes `params.mode`/`genre`/… through to query params without switching on specific `mode` values, so dropping `genre`/`artist`/`album` from the enum does not break it. No edit needed — this step is a verification, not a change. (Optionally, the consumer may want `reference` on the wire; that is handled in Task 4's serialization decision, not here.)

- [ ] **Step 5: Run codegen — verify tsc passes and bindings regenerate**

Run: `corepack yarn codegen`
Expected: completes without TypeScript errors; `nitrogen/generated/**/SearchMode.{swift,kt}` now list the 12 new cases (no `ARTIST`/`ALBUM`/`GENRE`), and `MediaReference.{swift,kt}` + a `reference` field on the generated `SearchParams` now exist.

Then run: `npx tsc --noEmit`
Expected: 0 errors.

- [ ] **Step 6: Verify the regenerated enums**

Run: `git -C . status nitrogen/ && grep -ci "music_video\|musicVideo\|station\|podcast" nitrogen/generated/android/kotlin/com/margelo/nitro/audiobrowser/SearchMode.kt`
Expected: `nitrogen/` shows regenerated files; grep ≥ 3 (the new vertical cases exist). Confirm `SearchMode.kt` no longer contains `GENRE`/`ARTIST`/`ALBUM`.

- [ ] **Step 7: Commit**

```bash
git add src/types/browser.ts src/web/NativeAudioBrowser.ts nitrogen/ lib/
git commit -m "feat(search): reshape SearchMode to container verticals + add reference axis (TS + codegen)"
```

---

## Task 2: iOS `MediaIntentCriteria` — `Reference` enum, 3-way `isResume`, mode = mediaType only

**Files:**

- Modify: `ios/Player/MediaIntentCriteria.swift` (whole struct)
- Test: `ios/Tests/MediaIntentCriteriaTests.swift` (rewrite expectations)

**Interfaces:**

- Consumes: nothing from Task 1 at the Swift-test level (this type is `Intents`-free and string-based; it does not import the Nitro `SearchMode`).
- Produces: `MediaIntentCriteria.Reference` (`.my`/`.currentlyPlaying`/`.unknown`); `MediaIntentCriteria(... reference: Reference ...)` initializer (replacing `hasReference: Bool`); `MediaIntentCriteria.from(..., reference: Reference, mediaTypeMode: String?, ...)` factory; `criteria.searchMode` now equals `mediaTypeMode` verbatim; `criteria.reference` exposed for the funnel. Task 3 consumes these.

- [ ] **Step 1: Rewrite the failing tests for the new behavior**

Replace the entire body of `ios/Tests/MediaIntentCriteriaTests.swift` with:

```swift
import Testing

@testable import AudioBrowserTestable

@Suite("MediaIntentCriteria")
struct MediaIntentCriteriaTests {
  @Test func emptyEverything_isResume() {
    let c = MediaIntentCriteria(query: "", reference: .unknown, hasGenres: false, hasMediaType: false, matchesAppName: false)
    #expect(c.isResume)
  }

  @Test func whitespaceQuery_isResume() {
    let c = MediaIntentCriteria(query: "   ", reference: .unknown, hasGenres: false, hasMediaType: false, matchesAppName: false)
    #expect(c.isResume)
  }

  // currentlyPlaying always resumes — "play this" makes the active track play.
  @Test func currentlyPlaying_isResume() {
    #expect(MediaIntentCriteria(query: "", reference: .currentlyPlaying, hasGenres: false, hasMediaType: false, matchesAppName: false).isResume)
    // even with a stray query/filter, "play this" is a resume
    #expect(MediaIntentCriteria(query: "jazz", reference: .currentlyPlaying, hasGenres: true, hasMediaType: true, matchesAppName: false).isResume)
  }

  // .my always searches — "play my favorites" goes to the consumer, never resume.
  @Test func my_isNotResume() {
    #expect(!MediaIntentCriteria(query: "", reference: .my, hasGenres: false, hasMediaType: false, matchesAppName: false).isResume)
    #expect(!MediaIntentCriteria(query: "", reference: .my, hasGenres: false, hasMediaType: false, matchesAppName: true).isResume)
  }

  @Test func anyCriteria_isNotResume() {
    #expect(!MediaIntentCriteria(query: "kcrw", reference: .unknown, hasGenres: false, hasMediaType: false, matchesAppName: false).isResume)
    #expect(!MediaIntentCriteria(query: "", reference: .unknown, hasGenres: true, hasMediaType: false, matchesAppName: false).isResume)
    #expect(!MediaIntentCriteria(query: "", reference: .unknown, hasGenres: false, hasMediaType: true, matchesAppName: false).isResume)
  }

  @Test func queryNamesApp_isResume() {
    let c = MediaIntentCriteria(query: "Garden", reference: .unknown, hasGenres: false, hasMediaType: true, matchesAppName: true)
    #expect(c.isResume)
  }

  @Test func queryNamesApp_withGenre_isNotResume() {
    #expect(!MediaIntentCriteria(query: "Garden", reference: .unknown, hasGenres: true, hasMediaType: false, matchesAppName: true).isResume)
  }

  @Test func queryNotApp_isNotResume() {
    #expect(!MediaIntentCriteria(query: "jazz", reference: .unknown, hasGenres: false, hasMediaType: false, matchesAppName: false).isResume)
  }
}

@Suite("MediaIntentCriteria.from")
struct MediaIntentCriteriaFromTests {
  private func from(
    mediaName: String? = nil,
    genreNames: [String] = [],
    artistName: String? = nil,
    albumName: String? = nil,
    mediaTypeMode: String? = nil,
    reference: MediaIntentCriteria.Reference = .unknown,
    hasMediaType: Bool = false,
    appName: String? = "Radio Garden"
  ) -> MediaIntentCriteria {
    MediaIntentCriteria.from(
      mediaName: mediaName, genreNames: genreNames, artistName: artistName,
      albumName: albumName, mediaTypeMode: mediaTypeMode,
      reference: reference, hasMediaType: hasMediaType, appName: appName
    )
  }

  // MARK: resume vs search

  @Test func playAppName_resumes() {
    let c = from(mediaName: "Garden", hasMediaType: true)
    #expect(c.query == "Garden")
    #expect(c.matchesAppName)
    #expect(c.isResume)
  }

  @Test func emptyIntent_resumes() {
    let c = from()
    #expect(c.query == "")
    #expect(c.isResume)
  }

  @Test func playMyFavorites_searches() {
    let c = from(reference: .my)
    #expect(!c.isResume)
    #expect(c.reference == .my)
  }

  @Test func stationName_searches() {
    let c = from(mediaName: "KCRW")
    #expect(c.query == "KCRW")
    #expect(!c.matchesAppName)
    #expect(!c.isResume)
    #expect(c.searchMode == nil)
  }

  // MARK: mode comes ONLY from mediaTypeMode (no field-derivation)

  // "Play jazz" → genre is a FILTER, not a mode. mode stays nil; genre set.
  @Test func genre_isFilterNotMode() {
    let c = from(genreNames: ["jazz"], hasMediaType: true)
    #expect(c.query == "jazz")
    #expect(c.searchMode == nil)
    #expect(c.genre == "jazz")
    #expect(c.hasGenres)
    #expect(!c.isResume)
  }

  @Test func multiWordGenre_joined_noMode() {
    let c = from(mediaName: "   ", genreNames: ["classic", "rock"])
    #expect(c.query == "classic rock")
    #expect(c.genre == "classic rock")
    #expect(c.searchMode == nil)
  }

  @Test func artist_isFilterNotMode() {
    let c = from(artistName: "Michael Jackson")
    #expect(c.query == "Michael Jackson")
    #expect(c.searchMode == nil)
    #expect(c.artist == "Michael Jackson")
    #expect(!c.isResume)
  }

  @Test func albumAndArtist_areFiltersNotMode() {
    let c = from(mediaName: "Thriller", artistName: "Michael Jackson", albumName: "Thriller")
    #expect(c.searchMode == nil)
    #expect(c.album == "Thriller")
    #expect(c.artist == "Michael Jackson")
    #expect(!c.query.isEmpty)
  }

  // A vertical mediaType DOES set the mode, and travels with its filters.
  @Test func song_viaMediaTypeMode_setsTitle() {
    let c = from(mediaName: "Billie Jean", mediaTypeMode: "song")
    #expect(c.query == "Billie Jean")
    #expect(c.searchMode == "song")
    #expect(c.title == "Billie Jean")
    #expect(c.playlist == nil)
  }

  @Test func playlist_viaMediaTypeMode_setsPlaylist() {
    let c = from(mediaName: "Workout", mediaTypeMode: "playlist")
    #expect(c.query == "Workout")
    #expect(c.searchMode == "playlist")
    #expect(c.playlist == "Workout")
    #expect(c.title == nil)
  }

  // "Play jazz station" → vertical wins as mode; genre rides as a filter.
  @Test func station_withGenreFilter() {
    let c = from(genreNames: ["jazz"], mediaTypeMode: "station", hasMediaType: true)
    #expect(c.searchMode == "station")
    #expect(c.genre == "jazz")
  }

  @Test func podcast_setsMode() {
    let c = from(mediaName: "Serial", mediaTypeMode: "podcast", hasMediaType: true)
    #expect(c.searchMode == "podcast")
    #expect(c.query == "Serial")
  }

  @Test func plainName_hasNoStructuredFields() {
    let c = from(mediaName: "KCRW")
    #expect(c.searchMode == nil)
    #expect(c.genre == nil)
    #expect(c.artist == nil)
    #expect(c.album == nil)
  }
}
```

- [ ] **Step 2: Run tests to verify they fail (compile error / wrong shape)**

Run: `swift test --disable-sandbox --filter MediaIntentCriteria`
Expected: FAILS to compile — `MediaIntentCriteria` has no `Reference` type, the `reference:` initializer label doesn't exist, and `from` has no `mediaTypeMode:`/`reference:` params. (Ignore unrelated `PlaybackStateMachineTests`.)

- [ ] **Step 3: Rewrite `MediaIntentCriteria`**

Replace the whole struct in `ios/Player/MediaIntentCriteria.swift` with:

```swift
import Foundation

/// Normalized "what did the user ask to play", derived from a media intent.
/// Deliberately free of `Intents` types so the core (`HybridAudioBrowser`)
/// never imports the Intents framework — the mapping lives in the
/// `Intents`-aware `RNABMediaIntentHandler`.
public struct MediaIntentCriteria: Sendable {
  /// Media-reference axis (mirrors `INMediaReference`). `currentlyPlaying`
  /// routes to native resume and never reaches the consumer; `my` routes to
  /// the search source; `unknown` is the default.
  public enum Reference: Sendable { case my, currentlyPlaying, unknown }

  let query: String
  let reference: Reference
  let hasGenres: Bool
  let hasMediaType: Bool
  /// True when `query` is effectively the host app's own name. Siri turns
  /// "Play «app»" into a search for a word in the app name — e.g. "Play Radio
  /// Garden" arrives as mediaName "Garden" (+ radio mediaType). That's an
  /// app-open/resume, not a station search.
  let matchesAppName: Bool

  // Structured search payload, mirroring the shared `SearchParams`. Plain
  // Sendable strings so this type stays in the unit-testable target; the Nitro
  // `SearchParams` is assembled in the funnel.
  /// The container-vertical `SearchMode` name, or nil. Comes ONLY from the
  /// intent's media type — never derived from which filter field is set.
  let searchMode: String?
  let genre: String?
  let artist: String?
  let album: String?
  let title: String?
  let playlist: String?

  init(
    query: String,
    reference: Reference,
    hasGenres: Bool,
    hasMediaType: Bool,
    matchesAppName: Bool,
    searchMode: String? = nil,
    genre: String? = nil,
    artist: String? = nil,
    album: String? = nil,
    title: String? = nil,
    playlist: String? = nil
  ) {
    self.query = query
    self.reference = reference
    self.hasGenres = hasGenres
    self.hasMediaType = hasMediaType
    self.matchesAppName = matchesAppName
    self.searchMode = searchMode
    self.genre = genre
    self.artist = artist
    self.album = album
    self.title = title
    self.playlist = playlist
  }

  /// Resume vs search, by reference:
  /// - `currentlyPlaying` → always resume ("play this" plays the active track)
  /// - `my` → always search ("play my favorites" goes to the consumer)
  /// - `unknown` → the no-criteria / app-name heuristic
  var isResume: Bool {
    switch reference {
    case .currentlyPlaying: return true
    case .my: return false
    case .unknown:
      let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
      // "Play «app»": no search term and no other filter → resume.
      if q.isEmpty { return !hasGenres && !hasMediaType }
      // "Play «app-name»": resume — unless a real filter signals a search.
      return matchesAppName && !hasGenres
    }
  }

  /// Builds criteria from the raw fields of a media-search intent. Pure (no
  /// `Intents`/`Bundle` dependency) so the whole Siri-phrase → search decision
  /// is unit-testable.
  ///
  /// `mediaTypeMode` is the already-collapsed `SearchMode` string for the
  /// intent's container vertical (e.g. "station"/"podcast"/"song"), or nil for
  /// a filter-only / unclassified type. `reference` is the mapped axis.
  static func from(
    mediaName: String?,
    genreNames: [String],
    artistName: String?,
    albumName: String?,
    mediaTypeMode: String?,
    reference: Reference,
    hasMediaType: Bool,
    appName: String?
  ) -> MediaIntentCriteria {
    let name = (mediaName ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    let trimmedNonEmpty: (String?) -> String? = {
      let t = ($0 ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
      return t.isEmpty ? nil : t
    }

    let genre = genreNames.isEmpty ? nil : genreNames.joined(separator: " ")
    let artist = trimmedNonEmpty(artistName)
    let album = trimmedNonEmpty(albumName)
    // song/playlist carry their spoken name into a dedicated field.
    let title = mediaTypeMode == "song" ? (name.isEmpty ? nil : name) : nil
    let playlist = mediaTypeMode == "playlist" ? (name.isEmpty ? nil : name) : nil

    // `query` is always populated so search works even before the API honours
    // `mode`: prefer the spoken name, else fall back to a structured value.
    let query = name.isEmpty ? (genre ?? artist ?? album ?? title ?? playlist ?? "") : name

    return MediaIntentCriteria(
      query: query,
      reference: reference,
      hasGenres: !genreNames.isEmpty,
      hasMediaType: hasMediaType,
      matchesAppName: queryMatchesAppName(query, appName: appName),
      searchMode: mediaTypeMode,   // mode is the vertical, verbatim — no derivation
      genre: genre,
      artist: artist,
      album: album,
      title: title,
      playlist: playlist
    )
  }

  /// Whether `query` is effectively the host app's own name. Case- and
  /// diacritic-insensitive.
  private static func queryMatchesAppName(_ query: String, appName: String?) -> Bool {
    guard let appName else { return false }
    let normalize: (String) -> String = {
      $0.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
        .trimmingCharacters(in: .whitespacesAndNewlines)
    }
    let q = normalize(query), a = normalize(appName)
    guard !q.isEmpty, !a.isEmpty else { return false }
    return a.contains(q) || q.contains(a)
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `swift test --disable-sandbox --filter MediaIntentCriteria`
Expected: all `MediaIntentCriteria` and `MediaIntentCriteria.from` tests PASS. (Ignore unrelated `PlaybackStateMachineTests` failures.)

- [ ] **Step 5: Commit**

```bash
git add ios/Player/MediaIntentCriteria.swift ios/Tests/MediaIntentCriteriaTests.swift
git commit -m "feat(ios): MediaIntentCriteria reference enum + mode-from-mediaType only"
```

---

## Task 3: iOS handler + funnel — map `INMediaReference`/`INMediaItemType`, assemble `reference`

**Files:**

- Modify: `ios/CarPlay/RNABMediaIntentHandler.swift` (the `handle` mapping)
- Modify: `ios/HybridAudioBrowser.swift:1633-1645` (funnel `SearchParams` assembly)
- Modify: `ios/Browser/BrowserManager.swift:627` (text-search `SearchParams` constructor)
- Test: iOS app build (`swift test` covers the pure unit in Task 2; this task is integration, verified by a clean compile of the iOS target)

**Interfaces:**

- Consumes: `MediaIntentCriteria.Reference`, `MediaIntentCriteria.from(... mediaTypeMode:reference:hasMediaType: ...)` (Task 2); the regenerated Nitro `SearchParams` with its required `reference` field and `MediaReference` enum (Task 1).
- Produces: a `SearchParams` whose `reference` is `.my` or `.unknown`; `.currentlyPlaying` never reaches assembly (resume handles it).

- [ ] **Step 1: Map `INMediaItemType` → collapse string and `INMediaReference` → `Reference` in the handler**

In `ios/CarPlay/RNABMediaIntentHandler.swift`, replace the body of `handle(intent:completion:)` that computes `mediaTypeKind` and builds the criteria with the following. The `mediaTypeMode(_:)` helper encodes the canonical collapse table (see Global Constraints); the `reference(_:)` helper maps the axis:

```swift
func handle(intent: INPlayMediaIntent, completion: @escaping @Sendable (INPlayMediaIntentResponse) -> Void) {
  let s = intent.mediaSearch
  let criteria = MediaIntentCriteria.from(
    mediaName: s?.mediaName,
    genreNames: s?.genreNames ?? [],
    artistName: s?.artistName,
    albumName: s?.albumName,
    mediaTypeMode: Self.mediaTypeMode(s?.mediaType ?? .unknown),
    reference: Self.reference(s?.reference ?? .unknown),
    hasMediaType: (s?.mediaType ?? .unknown) != .unknown,
    appName: Self.hostAppName()
  )
  Self.logger.info("Play media intent — query=\(criteria.query) matchesApp=\(criteria.matchesAppName) resume=\(criteria.isResume)")

  HybridAudioBrowser.handlePlayMediaIntent(criteria: criteria) { success in
    completion(INPlayMediaIntentResponse(code: success ? .success : .failure, userActivity: nil))
  }
}

/// Collapse `INMediaItemType` to a `SearchMode` string (container vertical), or
/// nil for filter-only / unclassified types. See the plan's canonical table.
private static func mediaTypeMode(_ type: INMediaItemType) -> String? {
  switch type {
  case .station, .radioStation, .algorithmicRadioStation, .musicStation: return "station"
  case .podcastShow, .podcastEpisode, .podcastPlaylist, .podcastStation:  return "podcast"
  case .audioBook:       return "audiobook"
  case .news:            return "news"
  case .music:           return "music"
  case .song:            return "song"
  case .playlist:        return "playlist"
  case .musicVideo:      return "music-video"
  case .movie:           return "movie"
  case .tvShow:          return "tv-show"
  case .tvShowEpisode:   return "tv-show-episode"
  default:               return nil   // album/artist/genre/unknown → filter or unclassified
  }
}

/// Map the SiriKit reference to the pure criteria enum.
private static func reference(_ ref: INMediaReference) -> MediaIntentCriteria.Reference {
  switch ref {
  case .currentlyPlaying: return .currentlyPlaying
  case .my:               return .my
  default:                return .unknown
  }
}
```

Keep the existing `hostAppName()` helper as-is. Remove the old `mediaTypeKind` switch entirely (it's replaced by `mediaTypeMode`).

> Note: `INMediaReference.my` requires iOS 14.5+. The library targets iOS 16+, so no availability guard is needed. Some `INMediaItemType` cases (`algorithmicRadioStation`, `musicStation`, `news`, `podcastStation`) are iOS 14+/15+; all are within the deployment target.

- [ ] **Step 2: Assemble `reference` into the funnel's `SearchParams`**

In `ios/HybridAudioBrowser.swift`, the funnel's search branch builds `SearchParams` at `:1637`. Replace that constructor (currently `:1637-1645`) with:

```swift
        let params = SearchParams(
          mode: criteria.searchMode.flatMap { SearchMode(fromString: $0) },
          query: criteria.query,
          genre: criteria.genre,
          artist: criteria.artist,
          album: criteria.album,
          title: criteria.title,
          playlist: criteria.playlist,
          reference: criteria.reference == .my ? .my : .unknown
        )
```

(`.currentlyPlaying` cannot reach here — `isResume` returned `true` and routed to the resume branch above, so the ternary is total.)

- [ ] **Step 3: Add `reference` to the text-search constructor**

`ios/Browser/BrowserManager.swift:627` constructs a `SearchParams` for the plain `search(_ query: String)` overload. Replace that line:

```swift
      SearchParams(mode: nil, query: query, genre: nil, artist: nil, album: nil, title: nil, playlist: nil, reference: .unknown)
```

- [ ] **Step 4: Build the iOS target to verify it compiles against the new bindings**

Run (from the example app's iOS dir, or the library's Swift build as configured): the project's iOS build (e.g. `cd apps/example-native/ios && pod install && xcodebuild -scheme AudioBrowserExample -sdk iphonesimulator build` — use the repo's standard build command).
Expected: compiles with no errors referencing `SearchMode`, `SearchParams.reference`, or `MediaReference`. (`pod install` here is required because Task 1 regenerated iOS bindings — new generated files.)

- [ ] **Step 5: Commit**

```bash
git add ios/CarPlay/RNABMediaIntentHandler.swift ios/HybridAudioBrowser.swift ios/Browser/BrowserManager.swift
git commit -m "feat(ios): map reference + mediaType verticals into SearchParams"
```

---

## Task 4: Android `parseSearchIntent` — mode trim + `reference = 'unknown'`

**Files:**

- Modify: `android/src/main/java/com/audiobrowser/Service.kt:186-216` (mode mapping + `SearchParams` return)
- Modify: `android/src/main/java/com/audiobrowser/browser/BrowserManager.kt:721`, `:777` (`SearchParams` constructors), `:1247` (optional serialization)
- Test: Android build (`corepack yarn android:bundle` / gradle compile via the app)

**Interfaces:**

- Consumes: the regenerated Kotlin `SearchMode` (no `GENRE`/`ARTIST`/`ALBUM`; new vertical cases) and `MediaReference` enums, and the `reference` field on the generated `SearchParams` (Task 1).
- Produces: Android `SearchParams` with `reference = MediaReference.UNKNOWN` always, and `mode` only ∈ {`ANY`, `SONG`, `PLAYLIST`, null}.

- [ ] **Step 1: Trim the focus → mode mapping**

In `Service.kt`, replace the `mode` `when (mediaFocus)` block (`:187-199`) with one that no longer emits genre/artist/album (those focuses now yield `null`; the genre/artist/album extras still populate their props below):

```kotlin
    // Determine search mode (container vertical) from the media focus. Genre/
    // artist/album focuses are FILTERS, not verticals — they yield no mode; the
    // extras below carry them. Android has no station/podcast/etc. focus, so
    // those verticals are iOS-only.
    val mode =
      when (mediaFocus) {
        "vnd.android.cursor.item/*" ->
          if (query.isEmpty()) SearchMode.ANY else null
        "vnd.android.cursor.item/audio" -> SearchMode.SONG
        MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE -> SearchMode.PLAYLIST
        else -> null // genre/artist/album/unknown focus → no vertical
      }
```

- [ ] **Step 2: Add `reference` to the `parseSearchIntent` return**

Replace the `return SearchParams(...)` at `Service.kt:208-216` with (append `reference`):

```kotlin
    return SearchParams(
      mode = mode,
      query = query,
      genre = genre,
      artist = artist,
      album = album,
      title = title,
      playlist = playlist,
      reference = MediaReference.UNKNOWN,
    )
```

Ensure `com.margelo.nitro.audiobrowser.MediaReference` is imported (add the import alongside the existing `SearchMode` import).

- [ ] **Step 3: Add `reference` to the other Kotlin `SearchParams` constructors**

`BrowserManager.kt:721` and `:777` construct `SearchParams` (the internal `search(query)` overload and one other). Add `reference = MediaReference.UNKNOWN,` to each constructor's arguments. Add the `MediaReference` import to `BrowserManager.kt` if absent.

- [ ] **Step 4: (Optional) serialize `reference` onto the wire**

At `BrowserManager.kt:1247`, the search query map is built with `params.mode?.let { put("mode", it.toString().lowercase()) }`. Directly below it, add reference serialization so a consumer's HTTP search endpoint can see `'my'`:

```kotlin
          if (params.reference == MediaReference.MY) put("reference", "my")
```

(Only `'my'` is put on the wire; `'unknown'` is the absent default. Mirror this in the iOS serializer and web `SearchManager` only if the consuming app's search API consumes `reference` — otherwise leave the wire unchanged. This step is optional and may be skipped if no consumer reads it yet.)

- [ ] **Step 5: Build Android to verify it compiles against the new bindings**

Run: `corepack yarn android:bundle` (or the app's gradle compile).
Expected: compiles with no unresolved `SearchMode.GENRE`/`ARTIST`/`ALBUM` references and no missing-`reference`-argument errors.

- [ ] **Step 6: Commit**

```bash
git add android/src/main/java/com/audiobrowser/Service.kt android/src/main/java/com/audiobrowser/browser/BrowserManager.kt
git commit -m "feat(android): trim mode to verticals + always-unknown reference"
```

---

## Task 5: Integration — `pod install`, full rebuild, device/sim voice verification

**Files:**

- No source changes expected; this task verifies the bundle end-to-end and captures any integration fixes.

**Interfaces:**

- Consumes: everything from Tasks 1–4.

- [ ] **Step 1: Regenerate + reinstall pods in the consuming context**

Run: `corepack yarn codegen` (idempotent re-check) then, in the consuming iOS project, `pod install`.
Expected: no diff from codegen (Task 1 already committed it); pods install cleanly with the regenerated bindings.

- [ ] **Step 2: Full library checks**

Run: `npx tsc --noEmit` (0 errors) and `corepack yarn lint`.
Expected: clean.

- [ ] **Step 3: Run the full iOS unit suite**

Run: `swift test --disable-sandbox`
Expected: `MediaIntentCriteria*` suites pass. (Pre-existing `PlaybackStateMachineTests` failures are unrelated — ignore per Global Constraints.)

- [ ] **Step 4: Device/simulator voice checks (manual, scripted phrases)**

Build the example app to a device/simulator and verify each:

- "Hey Siri, play my favorites" → `reference='my'`, empty query → consumer `search` returns favorites and they play (no longer a no-op). **This is the headline fix.**
- "Hey Siri, play this" / "resume" → resumes the active or persisted track (native, no search).
- "Hey Siri, play jazz" → `genre='jazz'`, `mode` undefined → consumer searches genre.
- "Hey Siri, play a jazz podcast" → `mode='podcast'`, `genre='jazz'`.
- Android Auto "play <station>" → `mode` null/`SONG`/etc. as before, `reference='unknown'`; unaffected by the trim.

Expected: each behaves as described; no crash; the affinity/add intents (already shipped) still work.

- [ ] **Step 5: Commit any integration fixes**

```bash
git add -A
git commit -m "chore(search): integration pass for reference + SearchMode verticals"
```

---

## Self-Review notes (author)

- **Spec coverage:** `reference` axis (Tasks 1–4), `.currentlyPlaying`→resume (Task 2 `isResume` + Task 3 not-assembled), `.my`→consumer (Tasks 2–4), `SearchMode` trim+expand (Task 1, consumed 2–4), B4 dissolved (mode = mediaType only, Task 2), Android parity `reference='unknown'` (Task 4), web stub compile (Task 1). All covered.
- **Type consistency:** `MediaReference` (`'my'|'unknown'`) and `MediaIntentCriteria.Reference` (`.my/.currentlyPlaying/.unknown`) are deliberately different (public 2-value vs native 3-value); the funnel collapses `.my→.my`, else `.unknown` (Task 3 Step 2). `mediaTypeMode`/`searchMode` strings match the collapse table verbatim across Task 2 tests, Task 2 factory, and Task 3 handler.
- **Known coupling (not a defect):** native build is red between Task 1 and Task 4; each task is gated by its own signal. Flagged in Global Constraints.
