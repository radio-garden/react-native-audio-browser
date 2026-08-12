# Task 4 (iOS) — report

**Status:** DONE
**Build/test:** `swift test --disable-sandbox` → all 431 tests pass (incl. the previously-flagged `PlaybackStateMachineTests` — no failures this run); `swift build --disable-sandbox` → Build complete, exit 0. The gate Swift compiles and `HybridAudioBrowser` conforms to the regenerated `HybridAudioBrowserSpec_protocol`.

## What changed, per file

### `ios/HybridAudioBrowser.swift`

- **Emitter rename:** `browseGateChangedEmitter: Emitter<NativeBrowseGate?>` → `gateChangedEmitter: Emitter<Bool>`. It now carries only the active flag (set/clear); per-request chrome is no longer broadcast — surfaces obtain it at each serve site via `gateDecision(for:)`.
- **Callback props:** `onBrowseGateButtonPressed` → `onGateButtonPressed`. Added the two generated JS-set callback props the protocol now requires:
  - `resolveGate: (NativeGateRequest) -> Promise<Promise<GateDecision>>` (default returns an allow decision until JS installs a resolver).
  - `onGate: (GateEvent) -> Void` (default no-op).
- **State replacement:** dropped `browseGate: NativeBrowseGate?`. Added `defaultChrome: NativeGate?`, `hasResolver: Bool`, `isGateActive: Bool`, and `static let builtInGate = NativeGate(title: "Unavailable", message: nil, buttonTitle: nil)`.
- **Methods:** `setBrowseGate(gate:)` → `setGate(gate: NativeGate?, hasResolver: Bool)` (records default chrome + resolver flag, sets active, emits `true`). `clearBrowseGate()` → `clearGate()` (resets state, emits `false`). `getBrowseGate()` **deleted**.
- **Decision helper (the single choke point):** `@MainActor func gateDecision(for: NativeGateRequest) async -> GateOutcome` with `struct GateOutcome { let gated: Bool; let chrome: NativeGate? }`. Static fast path (no JS hop) when `!hasResolver`; otherwise awaits the resolver; chrome order is override → stored default → built-in.
- **Siri funnel (~1668):** the `params` assembly moved _above_ the gate check; the old `guard browser.browseGate == nil` became `let outcome = await browser.gateDecision(for: NativeGateRequest(reason: .search, path: nil, search: params))` → on `gated`, fires `browser.onGate(GateEvent(reason: .search))` then `completion(false)`. **The resume branch is untouched and still returns before the gate check** — resume is hearing, never gated; ordering preserved.

### `ios/CarPlay/CarPlayController.swift`

- `activeGate: NativeBrowseGate?` mirror → `isGated: Bool`; seeded from `browser.isGateActive`; the changed-emitter listener now calls `handleGateChanged(_ active: Bool)`.
- **Tab content (~425) — `showTabBar`:** when `isGated`, delegates to a new `showGatedTabBar(tabs:)` that resolves **each tab's path independently** via `gateDecision(for: NativeGateRequest(reason: .browse, path: tab.url, search: nil))`. A gated tab → a gate page built from that decision's chrome + `onGate(.browse)`; an allowed tab → a normal content shell that lazy-loads. This generalizes the old "every tab shows the gate" into per-path resolution. The empty-tabs case resolves the root path (`path: nil`).
- **Push-navigation (~693) — `navigateToUrl`:** made `async`. When `isGated`, resolves the pushed `url`; if gated, pushes the gate page (chrome) + `onGate(.browse)` instead of pushing content; otherwise pushes normally. Both call sites updated (`nowPlayingManager.navigateToUrl` closure wraps it in a `Task`; the selection `.browse` case `await`s it inside its existing `Task`).
- **Tab re-render guard (~793) — `handleTabsChanged`:** `activeGate == nil` → `!isGated`.
- **Gate handlers:** `handleBrowseGateChanged`/`applyGate`/`showGateTabBar` collapsed into `handleGateChanged(_:)`, which sets `isGated`, tears down pushed nav (`popToRootTemplate`), and rebuilds via `showTabBar` (routing to the gated or normal build). `removeGate` was removed (its job is now `showTabBar`'s ungated path). `makeGateTemplate` now takes `gate: NativeGate?` (built-in fallback inside) and calls `onGateButtonPressed`; its marker `userInfo` key renamed `"browseGate"` → `"gate"` (set-only, never read — purely documentary; gate pages are still identified by carrying no `path`).

### `ios/CarPlay/CarPlayNowPlayingManager.swift`

- The custom-now-playing-button gate (`audioBrowser?.browseGate == nil ? config… : []`) → `(audioBrowser?.isGateActive ?? false) ? [] : config.carPlayNowPlayingButtons`. Custom buttons still hide while gated; transport stays.

## How `resolveGate` is awaited

The generated property type is `(NativeGateRequest) -> Promise<Promise<GateDecision>>` (double Promise — same shape as the existing `searchCallback` precedent). `gateDecision` awaits it as `try? await resolveGate(request).await().await()`: the outer `.await()` yields the JS-side `Promise<GateDecision>`, the inner `.await()` yields the decision. `try?` ensures a thrown/rejected resolver falls through to "allow" and can never break the serve path.

## How the gate page renders with per-request chrome

`gateDecision` returns `GateOutcome.chrome` (override → stored default → built-in). Each serve site passes that chrome straight into the existing `makeGateTemplate(gate:tab:)` renderer — same CPListTemplate/enhanced-section-header construction as before, just fed per-request chrome instead of a single global gate. `makeGateTemplate` falls back to `HybridAudioBrowser.builtInGate` if handed `nil` (static gate with no default chrome).

## Concerns

- **CarPlay rendering went from "render the global gate once" to "resolve per tab/path".** The old code built every tab from one gate; the new `showGatedTabBar` loops tabs and awaits a decision each. For a static gate (no resolver) this is `tabs.count` cheap no-JS-hop decisions — behaviourally identical to before (every tab gated with the default chrome). With a resolver it issues one JS round-trip per tab on (re)render. That is the intended generalization (a resolver-allowed path now shows real content in the car), and tab counts are ≤4, but it is a real change in call volume vs. the old single-render.
- `onGate(.browse)` fires once **per gated tab** during a gated tab-bar build (no library-side dedup, per the design's "per gated serve" contract). A consumer wanting once-per-session debounces on its side.
- I did not touch `RNABMediaIntentHandler.swift` — the plan listed it, but the Siri search enforcement lives entirely in `HybridAudioBrowser.handlePlayMediaIntent`; the intent handler only forwards criteria. Its only "gate" reference is an unrelated readiness-latch comment.
- Per project rules I did NOT run codegen and did NOT touch `android/` or `src/`.
