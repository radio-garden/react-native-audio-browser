# Android NowPlayingUpdater Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract Player.kt's ~360-line Now Playing subsystem into a `NowPlayingUpdater` class behind a `NowPlayingSurface` seam — mirroring iOS's `NowPlayingUpdater` + `NowPlayingInfoController` split — so the override/flash/formatter precedence, generation guards, dedupe, and artwork keying become JVM-testable.

**Architecture:** `NowPlayingUpdater` owns all now-playing state and decisions; it talks to a small `NowPlayingSurface` interface (current track/index/state getters, field/artwork stamping, event emission) that `Player` implements with its existing `replaceMediaItem` code. The Nitro formatter `Func` is wrapped into a plain `suspend (FormatNowPlayingParams) -> NowPlayingUpdate?` at the Player boundary, and the coroutine scope is constructor-injected — so formatter races and flash-revert timing run under `runTest` virtual time.

**Tech Stack:** Kotlin, Media3 (only inside Player's surface impl), kotlinx-coroutines-test, JUnit4. Branch `nowplaying-updater` in the rnab-wt-nowplaying worktree; baseline 120 Android tests green.

**Hard constraints:** Nitro `Promise`/`Func` types are JNI-backed — never constructed in JVM tests (the suspend-lambda wrapper exists exactly for this). Nitro data classes (`NowPlayingUpdate`, `NowPlayingMetadata`, `FormatNowPlayingParams`, `TimedMetadata`, `Track`, `PlaybackError`) are plain and constructible. Test run: `cd apps/example-native/android && ./gradlew :react-native-audio-browser:testDebugUnitTest` (sandbox off). Format before committing: `./gradlew :react-native-audio-browser:format`.

---

### Task 1: `NowPlayingSurface` + `NowPlayingUpdater` core (precedence, dedupe, default render)

**Files:**
- Create: `android/src/main/java/com/audiobrowser/player/NowPlayingUpdater.kt`
- Test: `android/src/test/java/com/audiobrowser/player/NowPlayingUpdaterTest.kt`

- [ ] **Step 1: Write the failing tests** — a `FakeSurface` recording `stampFields` calls and exposing settable `currentTrack`/`currentIndex`/`playbackState`; tests for: (a) `render()` stamps track title/artist/album when no override; (b) override wins over track fields, null override fields fall back per-field; (c) flash wins over both and `getNowPlaying()` reflects flash > override > track; (d) identical fields are not re-stamped (dedupe), a changed track id re-stamps even with identical text; (e) `clearNowPlayingFlash()` re-renders the non-flash state.
- [ ] **Step 2: Run to verify compilation failure.**
- [ ] **Step 3: Implement** — `NowPlayingSurface` interface:

```kotlin
interface NowPlayingSurface {
  val currentIndex: Int?
  val currentTrack: Track?
  val playbackState: PlaybackState
  val playbackError: PlaybackError?
  val playWhenReady: Boolean
  val isRebuffering: Boolean
  val hasNowPlayingArtworkConfig: Boolean
  /** Stamp title/secondary/album onto the playing item (Player: buildUpon + replaceMediaItem). */
  fun stampFields(index: Int, track: Track, title: String?, secondaryLine: String?, album: String?)
  /** Stamp a resolved artwork uri onto the playing item. */
  fun stampArtwork(index: Int, track: Track, uri: String)
  /** Resolve now-playing artwork for [track] (Player: browserManager.resolveArtworkUrl + registry). */
  suspend fun resolveNowPlayingArtwork(track: Track, sizePx: Double): String?
  fun emitNowPlayingChanged(metadata: NowPlayingMetadata)
}
```

`NowPlayingUpdater(private val surface: NowPlayingSurface, private val scope: CoroutineScope)` — move the state fields (`nowPlayingOverride`, `nowPlayingFlash`/`nowPlayingFlashRevert`, `lastPublishedNowPlaying`/`PublishedNowPlaying`, `nowPlayingRenderGeneration`, `latestTimedMetadata`, `nowPlayingArtworkResolvedForTrackId`) and the logic of `applyNowPlayingMetadata` (renamed `render()`), `applyNowPlayingFields` (stamping delegated to `surface.stampFields` + `surface.emitNowPlayingChanged`), `updateNowPlaying`, `flashNowPlaying`, `clearNowPlayingFlash`, `cancelNowPlayingFlash`, `clearOverride`, `getNowPlaying` from Player.kt lines ~436–462 and ~996–1229 **verbatim where possible**, substituting `surface.` for the Player getters. Formatter/timed/artwork parts arrive in Tasks 2–3 — keep their call sites commented `// Task 2/3`.
- [ ] **Step 4: Tests pass.**
- [ ] **Step 5: Commit** `refactor(android): NowPlayingUpdater core behind a NowPlayingSurface seam`.

### Task 2: Formatter path with generation/track-id guards

- [ ] **Step 1: Failing tests** — formatter as injected `var formatter: (suspend (FormatNowPlayingParams) -> NowPlayingUpdate?)?` and `var enabled: Boolean`; under `runTest`: (a) formatter result applies (stamps formatted title/secondary); (b) a result completing after a *newer* `render()` is dropped (generation guard) — use a `CompletableDeferred` inside the fake formatter to control completion order; (c) a result completing after the track changed is dropped (id guard); (d) a throwing formatter leaves the default fields; (e) flash during an in-flight format wins and the late result is dropped; (f) params carry `playWhenReady`/`stalled`/`error` from the surface.
- [ ] **Step 2: Verify red.** **Step 3:** Move the formatter block from `applyNowPlayingMetadata` (Player.kt ~1128–1179) into `render()`, invoking the suspend lambda instead of the Nitro Func. **Step 4: green. Step 5: commit** `refactor(android): NowPlayingUpdater formatter path with testable race guards`.

### Task 3: Timed metadata + artwork keying + flash revert timing

- [ ] **Step 1: Failing tests** — (a) `onTimedMetadataReceived` stores and re-renders only when enabled+formatter set; timed metadata clears on `clearOverride()`; (b) artwork resolves once per track id (`resolveNowPlayingArtwork` called once across repeated renders), re-resolves for a new id, skips entirely without id, resets keying when `hasNowPlayingArtworkConfig` is false; a stale artwork result (track changed mid-resolve) is not stamped; (c) `flashNowPlaying(update, 300.0)` reverts after virtual 300ms (`advanceTimeBy`) and re-renders.
- [ ] **Step 2: red. Step 3:** Move `onTimedMetadataReceived` and `maybeResolveNowPlayingArtwork` (gating logic only — resolution+registry stay behind `surface.resolveNowPlayingArtwork`, stamping behind `surface.stampArtwork`); the 1200.0 size constant moves to the updater. **Step 4: green. Step 5: commit** `refactor(android): NowPlayingUpdater owns timed metadata and artwork keying`.

### Task 4: Wire Player; delete the moved code

**Files:** Modify `player/Player.kt`, `player/PlayerListener.kt`, `AudioBrowser.kt`.

- [ ] **Step 1:** Player implements `NowPlayingSurface` (getters delegate to existing properties; `stampFields`/`stampArtwork` keep the existing `buildUpon().setUri(localConfiguration?.uri)…replaceMediaItem` blocks; `resolveNowPlayingArtwork` wraps `browserManager.resolveArtworkUrl(track, config.nowPlayingArtwork, ImageContext(sizePx, sizePx))` + `artworkResolutions.register`; `hasNowPlayingArtworkConfig` reads `browser?.browserManager?.config?.nowPlayingArtwork != null`). Expose `val nowPlaying = NowPlayingUpdater(this, MainScope())`.
- [ ] **Step 2:** `setup()` sets `nowPlaying.enabled` and wraps the Nitro formatter: `nowPlaying.formatter = options.nowPlayingFormatter?.let { f -> { params -> f.invoke(params).await() } }`. `destroy()` calls `nowPlaying.destroy()` (cancels the scope). Delete the moved fields/functions from Player; keep thin delegations ONLY where the Nitro spec calls Player directly — otherwise update call sites: `AudioBrowser.kt:911–920` → `player.nowPlaying.updateNowPlaying/flashNowPlaying/clearNowPlayingFlash/getNowPlaying`; `PlayerListener.kt:51,107,116` → `player.nowPlaying.onTimedMetadataReceived/clearOverride/render`; `Player.setPlaybackState:1512` → `nowPlaying.render()`.
- [ ] **Step 3:** Full Android suite green (expect ~120 + new updater tests); `yarn test` green; format pass.
- [ ] **Step 4: Commit** `refactor(android): Player delegates now-playing to NowPlayingUpdater` — call out Player.kt size before/after in the message.

### Task 5: Verify + finish

- [ ] All three suites + lint; grep for leftovers (`applyNowPlayingMetadata`, `nowPlayingOverride` in Player). Device smoke by user (lock-screen metadata, flash, now-playing artwork). Then finishing-a-development-branch (merge to feature-fry expected).

## Self-review notes
- Verbatim-move steps reference exact Player.kt line ranges; new constructs (interface, wrapper, fakes) are fully specified.
- Behavior preserved: precedence, dedupe keying (index+trackId+fields), generation guards, artwork once-per-id keying, no-config keying reset, formatter error fallback. The ONLY semantic change: formatter invocation goes through a suspend-lambda wrapper (await depth unchanged — the wrapper does `.invoke(params).await()` exactly as today).
- `resolveDisplayArtwork`/`findQueueTrackByArtworkUri` stay in Player per scope decision.
