# iOS Siri "resume" media intent — Implementation Plan (Unit 1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a no-criteria Siri "play «app»" (`INPlayMediaIntent` with empty `INMediaSearch`) resume playback — warm (current queue) and cold-start (restore the last session), by porting the Android `PlaybackStateStore` contract to Swift.

**Architecture:** `RNABMediaIntentHandler` builds a plain `MediaIntentCriteria` (no Intents types) from the intent and passes it to `HybridAudioBrowser.handlePlayMediaIntent(criteria:)`. The handler branches: `isResume` → resume the current item, else cold-restore from a new `PlaybackStateStore`, else fall through to today's search path. `PlaybackStateStore` persists the current track + position + repeat/shuffle/speed to `UserDefaults` (mirroring `PlaybackStateStore.kt`), saving periodically + on pause + on track change, and restoring on the resume intent.

**Tech Stack:** Swift 6.2, iOS 16+, Apple `Testing` framework (`swift test --disable-sandbox`), `Intents`, `MediaPlayer`/`AVFoundation` (existing player). Library: `react-native-audio-browser`.

## Global Constraints

- **Pure Swift, no codegen.** No `*.nitro.ts` spec change, no `corepack yarn codegen`, no Kotlin/web-stub edits. `handlePlayMediaIntent` is a native-only method, not in the spec.
- **Keep Intents types out of the core.** `HybridAudioBrowser.swift` must not import `Intents`; the `INPlayMediaIntent` → `MediaIntentCriteria` mapping lives in `RNABMediaIntentHandler.swift` (which already imports `Intents`).
- **Live streams skip position** — persist/restore `nil` position when `track.live == true` (Android uses `C.TIME_UNSET`).
- **Single shared readiness/timeout budget** ≈ 8s over the whole resolve (per ADR 0002), not additive.
- **Test command:** `swift test --disable-sandbox` (sandbox off). Ignore the pre-existing `PlaybackStateMachineTests` failures.
- **Commit style:** lowercase `area: summary` (e.g. `resume: …`), matching existing history.

## File structure

| File | Responsibility | New/Modify |
|---|---|---|
| `ios/Player/MediaIntentCriteria.swift` | Plain struct: normalized "what to play" + `isResume`. No Intents types. | **Create** |
| `ios/CarPlay/RNABMediaIntentHandler.swift` | Map `INPlayMediaIntent` → `MediaIntentCriteria`; call `handlePlayMediaIntent(criteria:)`. | Modify |
| `ios/HybridAudioBrowser.swift` | `handlePlayMediaIntent(criteria:)` branch: resume / restore / search. Own a `PlaybackStateStore`. | Modify |
| `ios/Player/PersistedPlaybackState.swift` | `Codable` snapshot (track + position + settings) + `JsonTrack(from:)`. | **Create** |
| `ios/Player/PlaybackStateStore.swift` | `UserDefaults`-backed save/load/clear. | **Create** |
| `ios/TrackPlayer.swift` | Call `store.save(...)` on pause / track-change / periodic 5s. | Modify |
| `ios/Tests/MediaIntentCriteriaTests.swift` | Unit tests for `isResume`. | **Create** |
| `ios/Tests/PlaybackStateStoreTests.swift` | Unit tests for round-trip + clear. | **Create** |
| `Package.swift` | Add the three new `ios/Player/*.swift` sources to `AudioBrowserTestable`. | Modify |

---

### Task 1: `MediaIntentCriteria` + warm resume (1a)

**Files:**
- Create: `ios/Player/MediaIntentCriteria.swift`
- Create: `ios/Tests/MediaIntentCriteriaTests.swift`
- Modify: `ios/CarPlay/RNABMediaIntentHandler.swift`
- Modify: `ios/HybridAudioBrowser.swift` (signature of `handlePlayMediaIntent`)
- Modify: `Package.swift` (add `Player/MediaIntentCriteria.swift` to `AudioBrowserTestable` sources)

**Interfaces:**
- Produces: `struct MediaIntentCriteria { let query: String; let hasReference: Bool; let hasGenres: Bool; let hasMediaType: Bool; var isResume: Bool }`
- Produces: `HybridAudioBrowser.handlePlayMediaIntent(criteria: MediaIntentCriteria, completion: @escaping @Sendable (Bool) -> Void)` (replaces the `searchTerm:` variant)

- [ ] **Step 1: Write the failing test** — `ios/Tests/MediaIntentCriteriaTests.swift`

```swift
import Testing

@testable import AudioBrowserTestable

@Suite("MediaIntentCriteria")
struct MediaIntentCriteriaTests {
  @Test func emptyEverything_isResume() {
    let c = MediaIntentCriteria(query: "", hasReference: false, hasGenres: false, hasMediaType: false)
    #expect(c.isResume)
  }

  @Test func whitespaceQuery_isResume() {
    let c = MediaIntentCriteria(query: "   ", hasReference: false, hasGenres: false, hasMediaType: false)
    #expect(c.isResume)
  }

  @Test func anyCriteria_isNotResume() {
    #expect(!MediaIntentCriteria(query: "kcrw", hasReference: false, hasGenres: false, hasMediaType: false).isResume)
    #expect(!MediaIntentCriteria(query: "", hasReference: true, hasGenres: false, hasMediaType: false).isResume)
    #expect(!MediaIntentCriteria(query: "", hasReference: false, hasGenres: true, hasMediaType: false).isResume)
    #expect(!MediaIntentCriteria(query: "", hasReference: false, hasGenres: false, hasMediaType: true).isResume)
  }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `swift test --disable-sandbox --filter MediaIntentCriteria`
Expected: FAIL — `cannot find 'MediaIntentCriteria' in scope`.

- [ ] **Step 3: Create the struct** — `ios/Player/MediaIntentCriteria.swift`

```swift
import Foundation

/// Normalized "what did the user ask to play", derived from a media intent.
/// Deliberately free of `Intents` types so the core (`HybridAudioBrowser`)
/// never imports the Intents framework — the mapping lives in the ObjC-adjacent
/// `RNABMediaIntentHandler`.
struct MediaIntentCriteria {
  let query: String
  let hasReference: Bool
  let hasGenres: Bool
  let hasMediaType: Bool

  /// No actionable criteria at all → "just play / resume".
  var isResume: Bool {
    query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
      && !hasReference && !hasGenres && !hasMediaType
  }
}
```

- [ ] **Step 4: Add the source to the test target** — `Package.swift`

Add `"Player/MediaIntentCriteria.swift",` to the `AudioBrowserTestable` target's `sources` array (alongside the existing `"Model/NitroTypeStubs.swift"` entry).

- [ ] **Step 5: Run the test, verify it passes**

Run: `swift test --disable-sandbox --filter MediaIntentCriteria`
Expected: PASS (3 tests).

- [ ] **Step 6: Refactor `handlePlayMediaIntent` to take criteria + branch to warm resume** — `ios/HybridAudioBrowser.swift`

Replace the `handlePlayMediaIntent(searchTerm:completion:)` signature/body with:

```swift
public func handlePlayMediaIntent(criteria: MediaIntentCriteria, completion: @escaping @Sendable (Bool) -> Void) {
  Task { @MainActor in
    guard let (browser, player) = await playerAndConfiguredBrowser.wait(timeout: .seconds(8)) else {
      self.logger.error("handlePlayMediaIntent: browser/player not ready within budget")
      completion(false)
      return
    }
    guard browser.browseGate == nil else {
      self.logger.info("handlePlayMediaIntent: refused — browse gate is set")
      completion(false)
      return
    }

    // No-criteria intent ("play «app»") → resume.
    if criteria.isResume {
      if player.currentTrack != nil {
        player.play()
        self.showNowPlayingRequestedEmitter.emit(())
        completion(true)
      } else {
        // Cold start: nothing loaded yet. Task 5 restores from PlaybackStateStore here.
        completion(false)
      }
      return
    }

    do {
      let resolved = try await browser.browserManager.search(criteria.query)
      let tracks = (resolved.children ?? []).filter { $0.src != nil }
      guard !tracks.isEmpty else { completion(false); return }
      player.setQueue(tracks, initialIndex: 0, playWhenReady: true)
      self.showNowPlayingRequestedEmitter.emit(())
      completion(true)
    } catch {
      self.logger.error("handlePlayMediaIntent failed: \(error.localizedDescription)")
      completion(false)
    }
  }
}
```

- [ ] **Step 7: Build the criteria in the intent handler** — `ios/CarPlay/RNABMediaIntentHandler.swift`

Replace the body of `handle(intent:completion:)` that computes `searchTerm` with:

```swift
func handle(intent: INPlayMediaIntent, completion: @escaping @Sendable (INPlayMediaIntentResponse) -> Void) {
  let s = intent.mediaSearch
  let criteria = MediaIntentCriteria(
    query: s?.mediaName ?? "",
    hasReference: (s?.reference ?? .unknown) != .unknown,
    hasGenres: !((s?.genreNames ?? []).isEmpty),
    hasMediaType: (s?.mediaType ?? .unknown) != .unknown,
  )
  Self.logger.info("Play media intent — query=\(criteria.query) resume=\(criteria.isResume)")

  guard let browser = HybridAudioBrowser.shared else {
    completion(INPlayMediaIntentResponse(code: .failureRequiringAppLaunch, userActivity: nil))
    return
  }
  browser.handlePlayMediaIntent(criteria: criteria) { success in
    completion(INPlayMediaIntentResponse(code: success ? .success : .failure, userActivity: nil))
  }
}
```

(If the compiler reports a different `INMediaSearch` property/enum name — e.g. `genreNames` vs `genres`, or `mediaType` not being `Optional` — adjust to the SDK's exact spelling; the intent is "true when that field carries a value".)

- [ ] **Step 8: Build the app and device-verify warm resume**

Build the consuming app (normal rebuild — no `pod install`, no codegen) and run on a device/CarPlay. Start a station, pause, then say "play «app»". Expected: playback resumes and Now Playing surfaces. (Cold start still no-ops — Task 5.)

- [ ] **Step 9: Commit**

```bash
git add ios/Player/MediaIntentCriteria.swift ios/Tests/MediaIntentCriteriaTests.swift \
        ios/CarPlay/RNABMediaIntentHandler.swift ios/HybridAudioBrowser.swift Package.swift
git commit -m "resume: warm resume for a no-criteria play intent via MediaIntentCriteria"
```

---

### Task 2: `PersistedPlaybackState` snapshot + `JsonTrack(from:)` (1b)

**Files:**
- Create: `ios/Player/PersistedPlaybackState.swift`
- Modify: `ios/Browser/JsonModels.swift` (add `JsonTrack(from track: Track)`)
- Modify: `Package.swift` (add `Player/PersistedPlaybackState.swift`)
- Test: covered in Task 3's round-trip test.

**Interfaces:**
- Produces: `struct PersistedPlaybackState: Codable { let track: JsonTrack; let positionMs: Double?; let repeatMode: String; let shuffleEnabled: Bool; let playbackSpeed: Float }`
- Produces: `extension JsonTrack { init(from track: Track) }`

- [ ] **Step 1: Add the snapshot type** — `ios/Player/PersistedPlaybackState.swift`

```swift
import Foundation

/// A serialisable snapshot of the player's resumable state. Mirrors Android's
/// `PlaybackStateStore.PersistedState`. `positionMs` is `nil` for live streams.
struct PersistedPlaybackState: Codable {
  let track: JsonTrack
  let positionMs: Double?
  let repeatMode: String
  let shuffleEnabled: Bool
  let playbackSpeed: Float
}
```

- [ ] **Step 2: Add `JsonTrack(from:)` so a live `Track` can be persisted** — `ios/Browser/JsonModels.swift`

```swift
extension JsonTrack {
  /// Snapshot the persistable subset of a live Track (inverse of `toNitro()`).
  init(from track: Track) {
    self.init(
      id: track.id,
      url: track.url,
      title: track.title,
      subtitle: track.subtitle,
      artwork: track.artwork,
      artist: track.artist,
      albumUrl: track.albumUrl,
      album: track.album,
      description: track.description,
      genre: track.genre,
      duration: track.duration,
      src: track.src,
      request: track.request.map(JsonTrackRequest.init(from:)),
      style: nil,
      childrenStyle: nil,
      groupTitle: track.groupTitle,
      live: track.live,
      imageRow: nil,
    )
  }
}
```

(Match `JsonTrack`'s real member init parameter order/names at `ios/Browser/JsonModels.swift`. If `JsonTrackRequest` has no `init(from:)`, add the trivial one or inline the fields; `style`/`imageRow` are display-only and safe to drop from the snapshot.)

- [ ] **Step 3: Register the source** — `Package.swift`

Add `"Player/PersistedPlaybackState.swift",` to `AudioBrowserTestable` sources.

- [ ] **Step 4: Build to confirm it compiles**

Run: `swift build --disable-sandbox`
Expected: builds (no test yet — exercised in Task 3).

- [ ] **Step 5: Commit**

```bash
git add ios/Player/PersistedPlaybackState.swift ios/Browser/JsonModels.swift Package.swift
git commit -m "resume: PersistedPlaybackState snapshot + JsonTrack(from:)"
```

---

### Task 3: `PlaybackStateStore` (UserDefaults) (1b)

**Files:**
- Create: `ios/Player/PlaybackStateStore.swift`
- Create: `ios/Tests/PlaybackStateStoreTests.swift`
- Modify: `Package.swift` (add `Player/PlaybackStateStore.swift`)

**Interfaces:**
- Consumes: `PersistedPlaybackState` (Task 2)
- Produces: `final class PlaybackStateStore { init(defaults: UserDefaults); func save(_:); func load() -> PersistedPlaybackState?; func clear() }`

- [ ] **Step 1: Write the failing test** — `ios/Tests/PlaybackStateStoreTests.swift`

```swift
import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("PlaybackStateStore")
struct PlaybackStateStoreTests {
  private func freshDefaults() -> UserDefaults {
    let suite = "PlaybackStateStoreTests"
    let d = UserDefaults(suiteName: suite)!
    d.removePersistentDomain(forName: suite)
    return d
  }

  private func sampleTrack() -> JsonTrack {
    JsonTrack(
      id: "abc", url: nil, title: "Test FM", subtitle: nil, artwork: nil,
      artist: "City, Country", albumUrl: nil, album: nil, description: nil,
      genre: nil, duration: nil, src: "/listen/abc/channel.mp3", request: nil,
      style: nil, childrenStyle: nil, groupTitle: nil, live: true, imageRow: nil,
    )
  }

  @Test func roundTrip_preservesState() {
    let store = PlaybackStateStore(defaults: freshDefaults())
    let state = PersistedPlaybackState(
      track: sampleTrack(), positionMs: nil, repeatMode: "off",
      shuffleEnabled: false, playbackSpeed: 1.0,
    )
    store.save(state)
    let loaded = store.load()
    #expect(loaded?.track.src == "/listen/abc/channel.mp3")
    #expect(loaded?.track.live == true)
    #expect(loaded?.positionMs == nil)
    #expect(loaded?.playbackSpeed == 1.0)
  }

  @Test func load_isNilBeforeAnySave() {
    #expect(PlaybackStateStore(defaults: freshDefaults()).load() == nil)
  }

  @Test func clear_removesState() {
    let store = PlaybackStateStore(defaults: freshDefaults())
    store.save(PersistedPlaybackState(track: sampleTrack(), positionMs: 5000, repeatMode: "off", shuffleEnabled: false, playbackSpeed: 1.0))
    store.clear()
    #expect(store.load() == nil)
  }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `swift test --disable-sandbox --filter PlaybackStateStore`
Expected: FAIL — `cannot find 'PlaybackStateStore' in scope`.

- [ ] **Step 3: Implement the store** — `ios/Player/PlaybackStateStore.swift`

```swift
import Foundation

/// Persists the player's resumable state to UserDefaults so a cold-start
/// "resume" intent can restore the last session without the JS runtime.
/// Swift counterpart of Android's `PlaybackStateStore.kt`.
final class PlaybackStateStore {
  private let defaults: UserDefaults
  private let key = "playbackState.v1"

  init(defaults: UserDefaults = UserDefaults(suiteName: "com.audiobrowser.playback") ?? .standard) {
    self.defaults = defaults
  }

  func save(_ state: PersistedPlaybackState) {
    guard let data = try? JSONEncoder().encode(state) else { return }
    defaults.set(data, forKey: key)
  }

  func load() -> PersistedPlaybackState? {
    guard let data = defaults.data(forKey: key) else { return nil }
    return try? JSONDecoder().decode(PersistedPlaybackState.self, from: data)
  }

  func clear() {
    defaults.removeObject(forKey: key)
  }
}
```

- [ ] **Step 4: Register the source** — `Package.swift`

Add `"Player/PlaybackStateStore.swift",` to `AudioBrowserTestable` sources.

- [ ] **Step 5: Run the tests, verify they pass**

Run: `swift test --disable-sandbox --filter PlaybackStateStore`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add ios/Player/PlaybackStateStore.swift ios/Tests/PlaybackStateStoreTests.swift Package.swift
git commit -m "resume: UserDefaults-backed PlaybackStateStore"
```

---

### Task 4: Save triggers in the player (1b)

**Files:**
- Modify: `ios/TrackPlayer.swift` (own a `PlaybackStateStore`; save on pause, on track change, periodic 5s)

**Interfaces:**
- Consumes: `PlaybackStateStore`, `PersistedPlaybackState`, `JsonTrack(from:)`
- Produces: side effect only — a persisted snapshot kept current during playback.

- [ ] **Step 1: Add a store + a snapshot helper** — `ios/TrackPlayer.swift`

Add a stored property `private let playbackStateStore = PlaybackStateStore()` and a helper that snapshots current state (live → `nil` position). Use the player's existing repeat/shuffle/speed accessors for the values (read them where `RemoteCommandController` reads them):

```swift
private func persistPlaybackState() {
  guard let track = currentTrack else { return }
  let positionMs: Double? = (track.live == true) ? nil : (currentPositionSeconds * 1000)
  playbackStateStore.save(
    PersistedPlaybackState(
      track: JsonTrack(from: track),
      positionMs: positionMs,
      repeatMode: repeatModeString,      // existing repeat state → "off"/"track"/"queue"
      shuffleEnabled: isShuffleEnabled,  // existing shuffle accessor
      playbackSpeed: Float(playbackRate),// existing rate accessor
    ),
  )
}
```

(Wire `currentPositionSeconds`, `repeatModeString`, `isShuffleEnabled`, `playbackRate` to the player's existing accessors — see how `ios/RemoteCommand/RemoteCommandController.swift` reads repeat/shuffle and how position is read for Now Playing. Don't add new state; read what's already there.)

- [ ] **Step 2: Call it on the lifecycle points**

- On pause/stop: call `persistPlaybackState()` where `setPlaybackState(playing:)` transitions to not-playing (`TrackPlayer.swift:609` area).
- On track change: call it where `currentTrack` becomes a new value (the same place Now Playing metadata is prepared, `prepareItem`, `TrackPlayer.swift:705` area).
- Periodic: start a 5s repeating `Task`/timer while playing that calls `persistPlaybackState()` (mirrors Android `launchPeriodicSave`, which skips position for live). Cancel it on stop.

- [ ] **Step 3: Build and device-verify persistence**

Build the app. Play a station for >5s, then kill the app from the app switcher. Inspect that a snapshot was written (add a temporary `logger.debug` in `save`, or read the `com.audiobrowser.playback` suite). Expected: a `playbackState.v1` entry exists after playback. Remove any temporary logging.

- [ ] **Step 4: Commit**

```bash
git add ios/TrackPlayer.swift
git commit -m "resume: persist playback state on pause / track-change / periodic"
```

---

### Task 5: Cold-start restore on the resume intent (1b)

**Files:**
- Modify: `ios/HybridAudioBrowser.swift` (own a `PlaybackStateStore`; restore in the `isResume` else-branch)

**Interfaces:**
- Consumes: `PlaybackStateStore.load()`, `JsonTrack.toNitro()`, `TrackPlayer.setQueue(_:initialIndex:startPositionMs:playWhenReady:)`

- [ ] **Step 1: Give the browser a store**

Add `private let playbackStateStore = PlaybackStateStore()` to `HybridAudioBrowser`. (Same default suite as the player's store — they share `com.audiobrowser.playback`.)

- [ ] **Step 2: Replace the cold-start no-op with a restore** — in `handlePlayMediaIntent(criteria:)`, the `criteria.isResume` branch's `else`:

```swift
if player.currentTrack != nil {
  player.play()
  self.showNowPlayingRequestedEmitter.emit(())
  completion(true)
} else if let state = self.playbackStateStore.load() {
  let track = state.track.toNitro()
  let startMs = (track.live == true) ? nil : state.positionMs
  player.setQueue([track], initialIndex: 0, startPositionMs: startMs, playWhenReady: true)
  self.showNowPlayingRequestedEmitter.emit(())
  completion(true)
} else {
  completion(false)   // nothing playing and nothing persisted
}
```

- [ ] **Step 3: Build and device-verify cold-start resume**

Build. Play a station, then **force-quit** the app. Then (from CarPlay / a Bluetooth control / "play «app»") trigger resume with the app not running. Expected: the app cold-launches, the last station reloads and plays (live → from live edge; non-live → from saved position), Now Playing shows.

- [ ] **Step 4: Run the full Swift test suite to confirm no regressions**

Run: `swift test --disable-sandbox`
Expected: the new `MediaIntentCriteria` + `PlaybackStateStore` suites pass; only the pre-existing `PlaybackStateMachineTests` failures remain (ignore per the global constraints).

- [ ] **Step 5: Commit**

```bash
git add ios/HybridAudioBrowser.swift
git commit -m "resume: cold-start restore from PlaybackStateStore on a resume intent"
```

---

### Task 6: Queue-expansion restore — Android parity (1b)

**Files:**
- Modify: `ios/HybridAudioBrowser.swift` (the cold-start restore branch in `handlePlayMediaIntent`, added in Task 5)

**Interfaces:**
- Consumes: `browserManager.expandQueueFromContextualUrl(_ url: String) async throws -> (tracks: [Track], selectedIndex: Int)?` (`ios/Browser/BrowserManager.swift:736`); `PlaybackStateStore.load()`; `JsonTrack.url: String?`; `JsonTrack.toNitro() -> Track`; `TrackPlayer.setQueue(_:initialIndex:startPositionMs:playWhenReady:)`.

**Why:** Android's resume re-derives the *full queue* from the saved track's contextual URL (`{parentPath}?__trackId={src}`) via `expandQueueFromContextualUrl` (resolve parent → siblings + selected index), not just the single track. iOS already stamps browse tracks with contextual URLs (`BrowserManager.swift:568-569`), so the persisted track carries one — match Android, falling back to the single track when the url isn't contextual (e.g. a bare live station) or expansion returns nil.

- [ ] **Step 1: Replace the Task 5 single-track restore block** — in `handlePlayMediaIntent(criteria:)`, the cold-start `else if let state = self.playbackStateStore.load()` branch:

```swift
} else if let state = self.playbackStateStore.load() {
  let track = state.track.toNitro()
  let startMs = (track.live == true) ? nil : state.positionMs
  // Match Android resume: re-expand the full queue from the track's contextual
  // URL (parent container → siblings + selected index). Fall back to the single
  // track when the url isn't contextual or expansion fails.
  if let url = state.track.url,
     let expanded = try? await browser.browserManager.expandQueueFromContextualUrl(url) {
    player.setQueue(expanded.tracks, initialIndex: expanded.selectedIndex, startPositionMs: startMs, playWhenReady: true)
  } else {
    player.setQueue([track], initialIndex: 0, startPositionMs: startMs, playWhenReady: true)
  }
  self.showNowPlayingRequestedEmitter.emit(())
  completion(true)
} else {
  completion(false)
}
```

- [ ] **Step 2: Confirm symbols against the real files** (cannot compile — `HybridAudioBrowser.swift` is outside the SPM target). Verify `expandQueueFromContextualUrl`'s return tuple labels (`tracks`, `selectedIndex`) at `ios/Browser/BrowserManager.swift:736` and that `browser.browserManager` is the right accessor (Task 1's search path uses `browser.browserManager.search`).

- [ ] **Step 3: Device-verify** (controller/user, app build): play a station reached by drilling into a list (so the queue has siblings), force-quit, trigger resume → expect the **whole list** restored as the queue with the right track selected. A bare live station with no siblings still restores as a single track.

- [ ] **Step 4: Commit**

```bash
git add ios/HybridAudioBrowser.swift
git commit -m "resume: restore the full queue from the contextual URL (Android parity)"
```

---

## Out of scope (follow-ups)

- **Proactive queue persistence without a contextual URL** — tracks played outside browse (e.g. raw voice-search results) have no contextual `__trackId`, so they restore single-track only. Persisting the queue explicitly for those is a later refinement.
- **Proactive Now-Playing on launch / `/__recent` browse root** (so Control Center/CarPlay shows the resumable item before the user asks).
- **The `resolvePlayMedia` JS hook** (Unit 2 — `.my`/sortOrder/genres) — this is the Nitro-bridge work, deliberately deferred.

## Self-review notes

- **Coverage:** structured criteria (Task 1), warm resume (Task 1), cold-start store (Tasks 2–4), cold-start restore (Task 5), live-position skip (Tasks 2/4/5), shared 8s budget (Task 1). All Unit-1 spec points covered.
- **Type consistency:** `MediaIntentCriteria` (Task 1) and `PersistedPlaybackState`/`PlaybackStateStore` (Tasks 2–3) signatures are reused verbatim in Tasks 4–5.
- **Un-unit-testable steps** (player playback, intent delivery, periodic save) are explicitly device-verified — that is their test; the pure-logic pieces (`isResume`, store round-trip) are TDD'd.
