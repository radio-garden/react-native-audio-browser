# Gate — Cross-Platform Parity / Post-Fix Thermonuclear Review

**Reviewer stance:** adversarial, review-only (no code changed).
**Scope:** verify the two fix waves (iOS `0a6ab0e`, Android `b0d59ad`) on `feature-fry-gate` (base `feature-fry`) are correct, regression-free, and leave iOS and Android **semantically identical**.
**Files read in full / cross-checked:** `ios/HybridAudioBrowser.swift` (`gateDecision`, `setGate`/`clearGate`, gate state, default `resolveGate`, Siri intent), `ios/CarPlay/CarPlayController.swift` (`gateBuildGeneration`, `showGatedTabBar`, `showTabBar`, `handleGateChanged`, `handleTabsChanged`, `navigateToUrl`), `android/.../AudioBrowser.kt` (`GateState`, `gateDecision`, `setGate`/`clearGate`, default `resolveGate`), `android/.../player/MediaSessionCallback.kt` (browse/search/`onGetItem` serve sites), `android/.../player/Player.kt` (`playFromSearch`), `src/features/gate.ts` + `gate.test.ts`, `src/web/NativeAudioBrowser.ts`, the design doc.

**Verdict:** Both fix waves do what they claim. The two headline defects from the prior reviews — iOS/Android fail-**open** and the Android `@Volatile` compound-read crash — are **genuinely fixed and now parity-correct** on the core serve path. Resume-never-gated holds on both platforms post-fix. **One real residual parity defect survives:** the iOS native default `resolveGate` was **not** flipped to fail-closed, while Android's was — so the init-window leak the Android wave closed (its own claim I4) is still **open on iOS**. Everything else is Minor / informational.

**Counts: 1 Critical · 1 Important · 4 Minor**

---

## CRITICAL

### C1 — iOS native default `resolveGate` still fails OPEN; Android's was flipped fail-CLOSED → init-window parity hole survives on iOS only

`ios/HybridAudioBrowser.swift:190-192` vs `android/.../AudioBrowser.kt:173-175`

The Android fix wave explicitly flipped its native default resolver to deny (its claim **I4**, verified):

```kotlin
// android AudioBrowser.kt:173 — FIXED to gated=true
override var resolveGate: ... = { Promise.resolved(Promise.resolved(GateDecision(gated = true, gate = null))) }
```

The iOS native default was **left untouched** and still allows:

```swift
// ios HybridAudioBrowser.swift:190 — STILL gated:false (fail OPEN)
public var resolveGate: (NativeGateRequest) -> Promise<Promise<GateDecision>> = { _ in
  Promise.resolved(withResult: Promise.resolved(withResult: GateDecision(gated: false, gate: nil)))
}
```

**Why this matters / reachability.** This native default is only consulted when `hasResolver == true` but the native `resolveGate` slot has not yet been (re)assigned by JS. JS assigns the real `nativeBrowser.resolveGate` at `gate.ts:111` (module load) and sets `hasResolver=true` via `setGate` afterwards, so the steady state is safe on both platforms. The window is the **JS-reload / instance-churn race**: native gate state (`hasResolver=true`) gets re-seeded (e.g. CarPlay re-seeds `isGated` from `isGateActive` at scene connect, `CarPlayController.swift:152`) and a serve fires `gateDecision` → `resolveGate(...)` **before** the gate.ts module re-runs its assignment. In that window:

- **Android** hits its default → `gated=true` → serves the gate. Safe (matches the wave's stated fail-closed direction).
- **iOS** hits its default → `gated=false` → `gateDecision` returns `GateOutcome(gated:false, chrome:nil)` → **serves real content / runs `searchPlayable` and plays** (Siri branch, `HybridAudioBrowser.swift:1746-1757`).

So the _exact_ fail-open content/paywall leak that C1/I4 set out to eliminate is still reachable on iOS via the init window. The iOS wave's own C1 narrative ("a _successful_ resolver returning `gated:false` still allows") technically holds — but the native default IS a successful `gated:false`, so the `try?`-level fail-closed guard (`:588`) never gets a chance to fire. The leak moved one layer up and was not closed on iOS.

This is a **true platform divergence introduced/left by the fix waves**: Android fails closed in the init window, iOS fails open. The rubric requires the fail-closed/allow distinction be "implemented identically"; here it is not.

**Severity rationale.** Critical because (a) it is a content/paywall leak on the precise error path a gate exists to hold, (b) it is a _divergence_ the waves were specifically chartered to eliminate, and (c) the Android wave already paid the cost and documented the rationale, so the iOS omission is a straight oversight, not a design call. Likelihood is low (init-window only), which is why it is one Critical and not a swarm — but "low-likelihood paywall bypass" is still the most important finding here.

**Recommended fix.** Flip the iOS native default to deny, mirroring Android, with the same comment:

```swift
public var resolveGate: (NativeGateRequest) -> Promise<Promise<GateDecision>> = { _ in
  // DENY by default: only reachable in the init window where hasResolver was
  // recorded true but JS hasn't re-bound resolveGate yet (JS reload / instance
  // churn). Same fail-closed direction as the resolver-error path in gateDecision.
  Promise.resolved(withResult: Promise.resolved(withResult: GateDecision(gated: true, gate: nil)))
}
```

Also flip the **web stub** `src/web/NativeAudioBrowser.ts:210` (`async () => ({ gated: false })`) for consistency, or document that web has no enforcement surfaces so its default is irrelevant. (Web has no CarPlay/AA sites, so the stakes are nil — note-only, but it's the third copy of the fail-open default and worth flipping so all three agree.)

---

## IMPORTANT

### I1 — iOS `gateBuildGeneration` token does not cover the non-gated `showTabBar` build, so a gated→cleared interleave can still double-set the root template (the very race the token was added to fix)

`ios/CarPlay/CarPlayController.swift:430-459` (non-gated `showTabBar`) vs `:467-529` (`showGatedTabBar`)

The token fix (iOS wave claim **I2**, partially verified) bumps `gateBuildGeneration` only at the top of **`showGatedTabBar`** and bails after each per-tab `gateDecision` await (`:486`, `:502`). That correctly serializes **two gated builds** against each other. But the **non-gated** `showTabBar` path (`:431`, the `else` of the `if isGated` branch at `:436`) **never touches `gateBuildGeneration`** — it calls `setRootTemplate` synchronously at `:453` and then `await loadContent` at `:457`.

Consider the headline clear sequence the token was meant to handle (a `set→clear`):

1. Gate is up. `handleGateChanged(true)` → `showTabBar` → `showGatedTabBar` enters, bumps generation to N, awaits `gateDecision` for tab 0 (main actor suspended).
2. `clearGate` lands → `gateChangedEmitter.emit(false)` → `handleGateChanged(false)` sets `isGated=false` (`:1091`), `popToRootTemplate`, `Task { await showTabBar }` (`:1100`). Because `isGated` is now false, this runs the **non-gated** branch, which **does not bump generation**, and calls `setRootTemplate(tabBar, …)` at `:453`.
3. The suspended gated build resumes. Its guard `gateBuildGeneration == generation` is **still true** (nobody bumped it), so it does **not** bail — it proceeds to `updateTemplates`/`setRootTemplate` at `:512-521`, painting a **gate page over the just-cleared content**.

So the generation guard protects gated-vs-gated but **not gated-vs-ungated**, which is the more common real transition (raising then clearing a gate). The prior iOS review's I2 even named "a rapid set→clear" as the motivating case; the implemented token does not actually cover the clear leg because the clear runs through the un-instrumented path. Net effect is the original stale/duplicate `setRootTemplate` + a visible gate-over-cleared-content flash — exactly what the fix claimed to remove. It is `@MainActor`-cooperative (no data corruption, no crash — CarPlay tolerates redundant set-root), so it is "stale render," matching the original I2 severity, not a crash.

**Recommended fix.** Make the generation a property of _any_ tab-bar build, not just the gated one: bump and capture `gateBuildGeneration` at the top of `showTabBar` (before the `if isGated` split) and re-check it after the `showGatedTabBar` awaits _and_ after the non-gated `await loadContent`. Simplest: have `handleGateChanged` (and `handleTabsChanged`) bump the generation when they kick a `Task { await showTabBar }`, and have both builds capture-and-check it around their awaits. That way a clear (ungated build) bumps the token and the in-flight gated build bails at `:486`/`:502`.

**Secondary (smaller) gap in the same area:** even within `showGatedTabBar`, the eager `loadContent` loop (`:525-528`) awaits per allowed tab with **no** generation re-check, so a supersede that lands during content-loading still finishes loading into templates that may already be detached. Lower impact (it mutates template content, not the root) but worth a guard for completeness.

---

## MINOR

### M1 — iOS static-path `defaultChrome ?? Self.builtInGate` is confirmed a no-op behavior change (regression check PASSED)

`ios/HybridAudioBrowser.swift:587`

The iOS wave added `?? Self.builtInGate` to the `!hasResolver` static return. Verified this changes no behavior: the static path is reached only when `hasResolver == false`, and the only call that sets `hasResolver=false` is `setGate(gate, false)` (`gate.ts:148`, `b != null` false), which always passes a concrete `nativeGate` → `defaultChrome` is **always non-nil** on the static path. The resolver-only overload (`setGate(resolve)`, `gate.ts:143`) sets `hasResolver=true`, so it never reaches the static branch with a nil chrome. The `?? builtInGate` is dead-but-safe belt-and-suspenders, and it makes the static path _total_ (mirrors Android's `s.chrome ?: builtInGate` at `AudioBrowser.kt:772`), which is good for parity. ✓ No regression.

### M2 — Android `setGate`/`clearGate` side-effects fully preserved across the data-class refactor (regression check PASSED)

`android/.../AudioBrowser.kt:731-745`

Verified the GateState refactor dropped none of the old side-effects:

- `setGate` still calls `connectedService?.player?.invalidateAllContent()` (`:737`) — the re-query/notify that swaps AA lists to the gate tile.
- `clearGate` retains the `if (!gateState.active) return` early-out (`:741`, was `if (!isGateActive) return`) **and** still calls `invalidateAllContent()` (`:744`).
- The new `GateState(...)` is fully constructed before the single `gateState = …` assignment in both setters (`:733`, `:743`) — no field-by-field mutation, so a reader cannot observe a half-built triple. ✓
- `gateDecision` snapshots `val s = gateState` once (`:770`) and reads only `s.active`/`s.hasResolver`/`s.chrome` thereafter — **no remaining read of an individual stale field**. ✓
- Every `gated=true` return guards chrome with `?: builtInGate` (`:772`, `:775`, `:777`), so the force-unwraps at the three serve sites (`MediaSessionCallback.kt:304`, `:458`, `:679`) can no longer NPE. The original C2 crash invariant is restored. ✓

iOS keeps emitting `gateChangedEmitter.emit(true/false)` in `setGate`/`clearGate` (`HybridAudioBrowser.swift:558`, `:568`) — its sole subscriber `handleGateChanged` (`CarPlayController.swift:315`) is intact. ✓ Neither platform's setter/clearer lost an emit or a re-query.

### M3 — Resume-never-gated re-confirmed post-fix on both platforms; the fixes did not route resume through `gateDecision`

`ios/HybridAudioBrowser.swift:1700-1726`; `android/.../player/MediaSessionCallback.kt:810` (`onPlaybackResumption`)

iOS: `if criteria.isResume { … return }` (`:1700-1726`) executes and unconditionally returns on all three sub-branches **before** `params` assembly (`:1731`) and the `gateDecision` call (`:1746`). The fix waves touched only the post-resume search branch. Resume never builds a request and never calls `gateDecision`. ✓
Android: `onPlaybackResumption` (`:810`) contains **no** `gateDecision` call (grep-confirmed: the only `gateDecision` sites are the two browse/search `onGetChildren`/`onSearch` paths, `onGetItem`, and `Player.playFromSearch`). `playFromSearch` is voice-_play_ (finding content), correctly gated; resume is a separate path that does not touch the gate. ✓ No fix-wave regression to resume.

### M4 — `onGate` trigger points unchanged by the fixes; per-serve, no dedup, parity holds (with the two pre-existing asymmetries the prior reviews already logged)

`ios/CarPlay/CarPlayController.swift:488/504/782`, `HybridAudioBrowser.swift:1751`; `android/.../MediaSessionCallback.kt:300/616/677`, `Player.kt:873`

Neither wave added or removed an `onGate` site. iOS fires per gated tab in `showGatedTabBar` (`:488`, `:504`), on the gated `navigateToUrl` push (`:782`), and on the gated Siri search (`:1751`). Android fires on the gated `onGetChildren` browse serve (`:300`), the two search sites (`:616`, `:677`), and gated `playFromSearch` (`:873`). Both suppress `onGate` on the gate-tile item re-read (iOS has no `onGetItem` equivalent; Android suppresses at `MediaSessionCallback.kt:445-446`). The pre-existing asymmetry — Android **also** fires `onGate` on `onGetChildren(GATE_PATH)` re-drill (`:289-300`) while suppressing it on `onGetItem(GATE_PATH)` — is unchanged (prior Android review **I3**, deferred-as-documented; not introduced by the fix). No double-fire or missed-fire introduced by either wave. The iOS token (I1) could in theory cause a **missed** `onGate` if a build bails after firing `onGate` for some tabs but before others — but `onGate` fires _after_ the generation guard at `:486`/`:502`, so a bailed build emits no spurious event; it just emits fewer (the superseding build re-emits). Acceptable under the "per serve, debounce on your side" contract.

---

## Fix-wave claim verification ledger

| Claim                                                                                                                             | Source                    | Verdict                                                                                                                                                                                            |
| --------------------------------------------------------------------------------------------------------------------------------- | ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| iOS C1 — fail CLOSED on resolver throw/reject/await-rejection                                                                     | review-ios "fix wave"     | **VERIFIED** at `HybridAudioBrowser.swift:588-592`. `try?` → nil now gates with `defaultChrome ?? builtInGate`; successful `gated:false` still allows (`:593`).                                    |
| iOS C1 — static path resolves `defaultChrome ?? builtInGate`                                                                      | review-ios                | **VERIFIED**, and confirmed a no-op behavior change (M1) since defaultChrome is always set there.                                                                                                  |
| iOS I2 — serialize gated builds via `gateBuildGeneration`                                                                         | review-ios                | **PARTIALLY VERIFIED / WANTING.** Serializes gated-vs-gated correctly, but does **not** cover the gated-vs-ungated (clear) interleave — the original "set→clear" motivating case still races (I1). |
| iOS docs (I1/M3) — onGate per-serve, setGate pops nav                                                                             | review-ios                | **VERIFIED** in `gate.ts:124-131` and `GateEvent` JSDoc.                                                                                                                                           |
| Android C2 — fold 3 `@Volatile` fields into one `GateState`, atomic assign, snapshot once, `?: builtInGate` on every gated return | review-android "fix wave" | **VERIFIED** (M2). No remaining individual stale-field read; force-unwraps now safe.                                                                                                               |
| Android C1 — fail CLOSED on resolver error                                                                                        | review-android            | **VERIFIED** at `AudioBrowser.kt:775` (`?: return GateOutcome(true, s.chrome ?: builtInGate)`); successful `gated:false` still allows (`:776`).                                                    |
| Android I4 — init-window default resolver flipped to `gated=true`                                                                 | review-android            | **VERIFIED** at `AudioBrowser.kt:174`. **But iOS was NOT given the matching flip → C1 above.**                                                                                                     |
| Android M4 — parity comment corrected                                                                                             | review-android            | **VERIFIED** at `AudioBrowser.kt:751-754`.                                                                                                                                                         |

---

## Parity table (post-fix)

| Property                         | iOS                                             | Android                                                      | Verdict                                                             |
| -------------------------------- | ----------------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------------- |
| Resolver throw/reject/timeout    | fail CLOSED (`:588-592`)                        | fail CLOSED (`:775`)                                         | **PARITY ✓**                                                        |
| Successful `gated:false`         | allow (`:593`)                                  | allow (`:776`)                                               | **PARITY ✓**                                                        |
| Static (no-resolver) path        | gate w/ `defaultChrome ?? builtInGate` (`:587`) | gate w/ `s.chrome ?: builtInGate` (`:772`)                   | **PARITY ✓**                                                        |
| Gated chrome order               | override → default → built-in (`:594`)          | override → default → built-in (`:777`)                       | **PARITY ✓**                                                        |
| Built-in fallback string         | `"Unavailable"` (`:544`)                        | `"Unavailable"` (`:714`)                                     | **PARITY ✓**                                                        |
| Init-window default resolver     | **`gated:false` (fail OPEN, `:191`)**           | `gated:true` (fail CLOSED, `:174`)                           | **DIVERGENCE — C1**                                                 |
| Concurrency model                | `@MainActor` serialized                         | single-`@Volatile`-ref snapshot                              | Different mechanism, both now sound for `gateDecision` (M2)         |
| Tab-bar build serialization      | `gateBuildGeneration`, gated-only               | n/a (AA re-queries are independent serves)                   | iOS token has a gated-vs-ungated gap (I1)                           |
| Resume never gated               | returns before gate check (`:1700`)             | `onPlaybackResumption` never calls `gateDecision`            | **PARITY ✓**                                                        |
| onGate per gated serve, no dedup | yes                                             | yes (+ pre-existing GATE_PATH re-drill asymmetry, unchanged) | **PARITY ✓** (modulo pre-existing I3, deferred)                     |
| Web stub default                 | `gated:false` (`:210`)                          | —                                                            | fail-open; harmless (no enforcement surface) but third copy to flip |

---

## Most important finding

**C1** — the iOS native default `resolveGate` (`HybridAudioBrowser.swift:191`) still returns `gated:false` while Android's was flipped to `gated:true` (`AudioBrowser.kt:174`). The init-window fail-open leak the Android wave closed (its claim I4) is **still open on iOS**, so the two platforms are **not** semantically identical on the error/init path the waves were chartered to align. Fix: flip the iOS default (and the web stub) to `gated:true`. Secondary: the iOS `gateBuildGeneration` token (I1) doesn't cover the clear (ungated) build, so the set→clear race it was added to fix still produces a stale gate-over-content paint.

---

## Verification run

- `yarn vitest run src/features/gate.test.ts` → **8 passed** (resolver true/false/Gate, static, setGate overloads, clearGate). Note: these exercise only the JS `resolveGate` wrapper; the native fail-closed-on-error behavior (C1's locus) has **no automated coverage** on either platform — recommend a native unit/integration test that drives `gateDecision` with a throwing resolver and asserts `gated=true`.
