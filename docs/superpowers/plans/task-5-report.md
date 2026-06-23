# Task 5 (Android) — report

**Status:** DONE
**Build:** `:react-native-audio-browser:compileDebugKotlin` (via the example app's Gradle, `ANDROID_HOME` pointed at the local SDK, `--offline`) → **BUILD SUCCESSFUL**. The hand-written Kotlin conforms to the regenerated `HybridAudioBrowserSpec` and compiles against the generated gate bindings. Only pre-existing deprecation warnings (TurboReactPackage, MediaStore, CommandButton, DynamicLoadControl) — none from the gate change.

## What changed, per file

### `android/src/main/java/com/audiobrowser/AudioBrowser.kt`
- **Imports:** dropped `NativeBrowseGate`; added `GateDecision`, `GateEvent`, `NativeGate`, `NativeGateRequest`.
- **Callback props (native→JS, set from JS, native CALLS them):** added the two the spec now requires:
  - `resolveGate: (request: NativeGateRequest) -> Promise<Promise<GateDecision>>` — default returns an allow decision (`Promise.resolved(Promise.resolved(GateDecision(gated = false, gate = null)))`) until JS installs a resolver.
  - `onGate: (event: GateEvent) -> Unit = {}` — default no-op.
- **Method rename:** `onBrowseGateButtonPressed` → `onGateButtonPressed`.
- **State replacement:** dropped `@Volatile browseGate: NativeBrowseGate?`. Added `@Volatile defaultChrome: NativeGate?`, `@Volatile hasResolver: Boolean`, `@Volatile isGateActive: Boolean` (all `@Volatile` for the same reason — written on the JS thread, read on the Media3 application thread). Added `private val builtInGate = NativeGate("Unavailable", null, null)` (matches the generated `title, message, buttonTitle` ctor order).
- **Methods:** `setBrowseGate(gate: NativeBrowseGate)` → `setGate(gate: NativeGate?, hasResolver: Boolean)` (records default chrome + resolver flag, sets active, re-queries subscribed parents via `invalidateAllContent()`). `clearBrowseGate()` → `clearGate()` (resets state, re-queries). `getBrowseGate()` **deleted**.
- **Decision helper (the single choke point):** `suspend fun gateDecision(request: NativeGateRequest): GateOutcome` with `data class GateOutcome(val gated: Boolean, val chrome: NativeGate?)`. No active gate → allow. Active gate, no resolver → static fast path (gate with stored default chrome, no JS hop). Active gate with a resolver → ask JS per request; chrome order on a gated decision is override → stored default → built-in. By contract, `chrome` is non-null whenever `gated` is true.

### `android/src/main/java/com/audiobrowser/player/MediaSessionCallback.kt`
- **Imports:** dropped `NativeBrowseGate`; added `GateEvent`, `GateReason`, `MediaReference`, `NativeGate`, `NativeGateRequest`, `SearchParams`.
- **`createGateMediaItem`:** signature `NativeBrowseGate` → `NativeGate` (now fed per-request chrome rather than the single global gate); doc updated.
- **`searchParams(query)` helper added:** wraps a raw external-search query string into a `SearchParams` (other fields null, `reference = MediaReference.UNKNOWN`) — mirrors BrowserManager's query→SearchParams construction. Used to build the `search` field of a `SEARCH` gate request.
- **`onGetChildren` (~270, browse):** the old `getBrowseGate()?.let { if (parentId != ROOT) … }` short-circuit → for any non-root `parentId`, `audioBrowser.gateDecision(NativeGateRequest(BROWSE, parentId, null))`; if gated → `onGate(GateEvent(BROWSE))` then serve the gate tile from `outcome.chrome!!`; else fall through to real children. Generalizes today's "every non-root path = gate tile" into per-path resolution.
- **`onGetItem` (~412, direct gate-tile fetch):** the old `getBrowseGate()` re-render → `gateDecision(NativeGateRequest(BROWSE, GATE_PATH, null))`; if not gated → `ofError(ERROR_BAD_VALUE)` (as before); else render `outcome.chrome!!`. **Does NOT emit `onGate`** — it's an item lookup of an already-served sentinel, not a fresh content serve.
- **`onSearch` (~565, search refuse/count):** the old `if (getBrowseGate() != null)` → `gateDecision(NativeGateRequest(SEARCH, null, searchParams(query)))`; if gated → `onGate(GateEvent(SEARCH))` + the existing `notifySearchResultChanged(…, 1, …)` + `ofVoid()`.
- **`onGetSearchResult` (~617, search gate tile):** the old `getBrowseGate()?.let { … }` → `gateDecision(SEARCH)`; if gated → `onGate(GateEvent(SEARCH))` + serve the gate tile from `outcome.chrome!!`.

### `android/src/main/java/com/audiobrowser/player/Player.kt`
- **Imports:** added `GateEvent`, `GateReason`, `NativeGateRequest`.
- **`playFromSearch(params)` (~863, voice "play X" refuse — the Android Auto analog of the iOS Siri `handlePlayMediaIntent` funnel):** the old `if (getBrowseGate() != null) return false` → `gateDecision(NativeGateRequest(SEARCH, null, params))`; if gated → `onGate(GateEvent(SEARCH))` + `return false`. (`params` is already a `SearchParams` here, so it's passed straight through.)

## How `resolveGate` is awaited in the coroutine
The generated property type is `(NativeGateRequest) -> Promise<Promise<GateDecision>>` (double Promise — same shape as the iOS `searchCallback`/`resolveAlbumUrl` precedent). Inside `gateDecision` (a `suspend fun`, called from the serve sites that already run in `scope.future { }` coroutines), it is awaited as:

```kotlin
val decision = runCatching { resolveGate(request).await().await() }.getOrNull()
```

The outer `.await()` yields the JS-side `Promise<GateDecision>`; the inner `.await()` yields the decision. `runCatching { … }.getOrNull()` ensures a thrown/rejected resolver never breaks the serve path — a null decision falls through to "allow". This mirrors the existing `formatter(params).await()` coroutine-await precedent in `AudioBrowser.setNavigationError` (~453).

## How the gate tile gets per-request chrome
`gateDecision` returns `GateOutcome.chrome` resolved per request (override → stored default → built-in). Each serve site passes that chrome straight into the existing `createGateMediaItem(gate: NativeGate)` renderer — same non-browsable/non-playable error-tile shape as before, just fed per-request chrome instead of one global gate. For a static gate (no resolver) this is a cheap no-JS-hop decision per path, behaviourally identical to the old "every non-root path gated with the default chrome".

## Concerns
- **`onGate` fires once per gated serve, no library-side dedup** (per the design's contract). A connected controller can re-query a path on `notifyChildrenChanged`, so a gated browse path fires `onGate(BROWSE)` on each re-query; a consumer wanting once-per-session debounces on its side. Matches the iOS report's noted behaviour.
- **Per-request decision is now made at five sites** (browse `onGetChildren`, item `onGetItem`, search `onSearch` + `onGetSearchResult`, voice-play `playFromSearch`). The plan named three; `onGetItem` and `playFromSearch` are the two additional pre-existing gate-read sites in the Android code (the latter is the Android Auto analog of the iOS Siri funnel mirrored in Task 4). All route through the single `gateDecision` choke point. `onGetItem` deliberately does not emit `onGate`.
- **Gate state holder:** the plan listed `BrowserManager.kt` as the gate state holder, but the actual gate state lives in `AudioBrowser.kt` (it always has). No gate code exists in `BrowserManager.kt`, so it was not touched — matches the iOS layout where the state lives in `HybridAudioBrowser`.
- Per project rules I did NOT run codegen and did NOT touch `ios/` or `src/`.
