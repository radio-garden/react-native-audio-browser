# iOS Gate — Thermonuclear Code Review

**Scope:** iOS gate implementation on `feature-fry-gate` (base `feature-fry`).
**Files reviewed in full:** `ios/HybridAudioBrowser.swift`, `ios/CarPlay/CarPlayController.swift`, `ios/CarPlay/CarPlayNowPlayingManager.swift`; cross-checked against `nitrogen/generated/ios/c++/HybridAudioBrowserSpecSwift.hpp`, `nitrogen/generated/ios/AudioBrowser-Swift-Cxx-Bridge.hpp`, `src/features/gate.ts`, `src/specs/audio-browser.nitro.ts`, the Android peer (`android/.../AudioBrowser.kt`), and the `resolveLayer` precedent (`ios/Browser/BrowserManager.swift:530`).
**Reviewer stance:** adversarial. Verdict up front: the implementation is correct on the resume-safety, cache-safety, threading, double-await, and rename axes the design cares most about. The one genuinely debatable decision — **fail-open on resolver error** — is deliberate and consistent across platforms, but it is the wrong default for the design's own stated use-case (a subscription gate) and is undocumented as a security decision. Everything else is Minor.

---

## Critical

### C1 — `gateDecision` fails **open** on resolver error, leaking gated content for a subscription gate
`ios/HybridAudioBrowser.swift:581`

```swift
guard let decision = try? await resolveGate(request).await().await(), decision.gated else {
  return GateOutcome(gated: false, chrome: nil)   // ← rejected/timed-out resolver ⇒ "allow"
}
```

`try?` collapses **two** distinct failure modes into "allow":
1. the JS resolver **throws / rejects**, and
2. either `.await()` layer rejects for an infrastructure reason (bridge tear-down on JS reload, the JS runtime being mid-reload, a serialization error).

For the design's own canonical example — `setGate({ title: 'Radio Premium', message: 'Subscribe to browse in the car.' }, …)`, a **subscription gate** — fail-open means: a resolver bug, an exception inside the consumer's predicate, or a transient bridge error causes the car surface to **serve the gated content** (browse paths render real content, and the Siri search branch runs `searchPlayable` and **plays**). That is a paywall bypass triggered by error, exactly the case a gate exists to prevent.

The design doc does **not** state a fail-open policy. It says the resolver is "a pure function of `(request, app state)`" and "must be synchronous and fast — no network." A pure, fast, local predicate that *throws* is a programming error, and the safe response to "I can't decide whether this is gated" for a gate whose purpose is to withhold content is to **withhold** (fail closed: gate with the stored default / built-in chrome), not to serve.

Note the asymmetry already baked in: when **no resolver** is installed (`!hasResolver`, line 580) the code fails *closed* (`gated: true`). Only the *resolver-present* path fails open. So a static gate is safe but the moment a consumer adds a resolver, an error in it flips the gate from "deny by default" to "allow by default." That is a surprising and dangerous inversion.

This is **consistent with Android** (`android/.../AudioBrowser.kt:754`, `runCatching{…}.getOrNull()` → same fail-open), so it is a *design-level* decision, not an iOS porting slip — which is why it is Critical rather than a one-line iOS fix: it needs a design ruling and then a matched change on both platforms.

**Recommended fix.** Decide the policy explicitly in the design doc, then make the *gated-default* the fail-safe:

```swift
let decision = try? await resolveGate(request).await().await()
guard let decision else {
  // Resolver error: fail CLOSED. We can't prove the request is allowed, and a
  // gate exists to withhold — serving on error is a content leak. Render the
  // stored default / built-in so the user still sees a coherent gate page.
  return GateOutcome(gated: true, chrome: defaultChrome ?? Self.builtInGate)
}
guard decision.gated else { return GateOutcome(gated: false, chrome: nil) }
return GateOutcome(gated: true, chrome: decision.gate ?? defaultChrome ?? Self.builtInGate)
```

If a consumer genuinely wants fail-open semantics (an advisory/upsell gate that must never block on error), that should be an explicit opt-in, not the silent default. At minimum, if fail-open is kept, the doc and the code comment must say **"on resolver error we SERVE the content (fail open) — do not use this gate to enforce a hard paywall"** so a consumer isn't misled by the "Subscribe to browse" example into thinking it's enforcing.

---

## Important

### I1 — `onGate` fires on the gated-tab loop but the design's `reason` can never distinguish which path was gated; and the "per gated serve" volume on a static gate is high and unbatched
`ios/CarPlay/CarPlayController.swift:485` (and `:470`, `:763`, and `HybridAudioBrowser.swift:1741`)

The design says `onGate` fires "per gated serve … no library-side dedup." The implementation honors that literally: `showGatedTabBar` fires `onGate(.browse)` **once per gated tab** on every tab-bar (re)build. With a static gate and 4 tabs, a single gate-raise (or any `handleTabsChanged` rebuild, locale switch, or `handleGateChanged`) emits **4** `onGate(.browse)` events in a tight loop. That is defensible per the contract, but two real problems:

1. **It is genuinely chatty on benign rebuilds.** `handleGateChanged` → `showTabBar` → `showGatedTabBar` runs on *every* gate set, including the in-place chrome update path (a second `setGate` while already gated re-emits `gateChangedEmitter(true)` → full rebuild → 4 more `onGate`). A consumer doing "record a gate hit" now records 4–8 per user action and must debounce hard. The design accepts this, but it is worth an explicit callout in `task-4-report.md`'s "Concerns" (it's there) **and** in the consumer-facing doc on `onGate`, which currently only says "fired when a request is gated" — it should say "fired once per gated tab/serve; debounce on your side."

2. **`GateEvent.reason` carries no path/identity**, so a consumer cannot tell *which* tab was gated or dedup by path — only `.browse` vs `.search`. Combined with (1), the 4 browse events are indistinguishable. This is the seam the design explicitly left open ("`path`/`search` on `GateEvent` — additive later"), so it's not a bug, but if the consumer's intended use (a deferred upsell) needs to know what was blocked, the event is currently too coarse to debounce meaningfully by content. Flagging so it's a conscious acceptance, not a surprise in the field.

**Recommended:** no code change required if the contract is truly "fire per serve," but (a) update the `onGate` doc comment in `gate.ts` to state the per-tab fan-out and the debounce expectation, and (b) confirm with the consumer that `.browse`/`.search`-only granularity is sufficient before this ships; if not, add `path?`/`search?` to `GateEvent` now (the struct was designed to allow it without a breaking change).

### I2 — `handleGateChanged` during an in-flight gated build can interleave two `showTabBar` tasks and double-set the root template
`ios/CarPlay/CarPlayController.swift:1071` → `:1081` → `showGatedTabBar` (`:459`)

`handleGateChanged(_:)` does `popToRootTemplate(...)` then `Task { await showTabBar(tabs:) }`. `showGatedTabBar` is `async` and **awaits a JS round-trip per tab** (when a resolver is installed). Nothing serializes these builds. If `gateChangedEmitter` fires again while a gated build is mid-await (a rapid set→clear, set→update, or a JS reload's instance churn that re-seeds `isGated` and rebuilds), you get **two concurrent `showGatedTabBar`/`showTabBar` tasks** both racing to `setRootTemplate` / `updateTemplates`.

Observed consequences:
- The `tabBar.templates.count == templates.count` in-place-swap check (`:493`) reads `interfaceController.rootTemplate` that the *other* in-flight task may be about to replace → the swap can target a tab bar that's already being torn down, or both tasks call `setRootTemplate`, the later-finishing one winning with possibly **stale** `isGated`/chrome (the resolver decisions were captured against the gate state at await-start).
- Because each `showGatedTabBar` re-reads `isGated` only at entry (it's captured via the `if isGated` branch in `showTabBar` at `:428`, *before* the per-tab awaits), a `clearGate` that lands mid-build won't abort the gated build — it'll finish painting gate pages, then the clear's own rebuild paints content. Net effect is a visible flash and, briefly, a gate page over already-cleared state. Not a crash (CarPlay template APIs are main-actor and tolerate redundant set-root), but a template-integrity / correctness smell.

This is **less severe than it looks** because: (a) every path is `@MainActor` so there's no data race on the Swift state itself — the interleaving is cooperative at `await` points, not preemptive; (b) `popToRootTemplate` and `setRootTemplate` are individually safe to call repeatedly. So it's "stale/duplicated render," not "corrupt stack." Still worth a guard.

**Recommended:** serialize gated builds with a generation token (mirroring the existing `albumArtistGeneration` pattern in `CarPlayNowPlayingManager`): bump a `gateBuildGeneration` in `handleGateChanged`, capture it before the per-tab loop, and bail after each `await` if it changed:

```swift
let generation = (gateBuildGeneration &+= 1, gateBuildGeneration).1
for tab in limitedTabs {
  let outcome = await audioBrowser.gateDecision(for: …)
  guard gateBuildGeneration == generation else { return }  // a newer build superseded us
  …
}
```

The same staleness applies to `navigateToUrl` (`:753`) awaiting a decision then pushing — but there the top-template check (`:764`/`:772`) absorbs most of it.

### I3 — Gated tabs lose the assistant ("Ask Siri to Play") cell, and an allowed tab swapped in via `updateTemplates` may not lazy-load
`ios/CarPlay/CarPlayController.swift:493-509`

Two distinct issues in the in-place-swap branch:

1. **Assistant cell loss is silent.** A non-gated tab template gets its `assistantCellConfiguration` only inside `updateTemplate` → `configureAssistantCell`, which runs from `loadContent`. In `showGatedTabBar`, allowed tabs are appended as bare `createTabTemplate` shells (`:489`) and then filled by the eager `loadContent` loop (`:506`). That's fine **on first build**. But on the `updateTemplates` in-place swap (`:497`), the comment claims "templateDidAppear isn't guaranteed to re-fire," which is why the eager `loadContent` loop exists — good — but it only loads tabs where `getPath(from: template) != nil` (`:507`). A **gate page** carries no path (its `userInfo` is `["gate": true]`), so the loop correctly skips it. The risk is the inverse: if `gateDecision` flips a tab from gated→allowed across two builds, the in-place `updateTemplates` swaps a fresh content shell in, and the eager loop *does* reload it — so this path is actually covered. **No bug here, but it hinges entirely on the eager loop**; if anyone later "optimizes" by trusting `templateDidAppear`, allowed-after-gated tabs go permanently blank. Worth a regression test / a louder comment.

2. **`updateTemplates` equal-count swap can mismatch tab identity.** The swap fires whenever `tabBar.templates.count == templates.count` (`:493`) — it does **not** check that the tabs are the same set/order. If the gate decisions changed *which* tabs are gated vs allowed but the count stayed equal, the swap reuses the existing tab bar but with reordered/repurposed children. CarPlay keeps the *selected index*, so the user can be left on what was tab 2 (content) now showing tab 2 (gate), with no visual transition. Low-probability, but the count-only guard is weaker than the ungated `handleTabsChanged` path (`:873`), which additionally checks `zip(...).allSatisfy { getPath == url }`. The gated path should apply the same per-slot identity check before choosing in-place vs full rebuild.

**Recommended:** for (2), gate the in-place swap on per-slot identity, not just count (compare each slot's tab `url` / gated-ness against the existing template). For (1), add a comment that the eager `loadContent` loop is load-bearing for allowed-tab content under `updateTemplates`, and ideally a manual-test entry for "resolver allows one tab, gates another."

---

## Minor

### M1 — Double-`.await().await()` is correct, but the inner-layer rejection is also swallowed by the single `try?`
`ios/HybridAudioBrowser.swift:581`

The unwrap is **genuinely correct** for the generated type. The C++ signature confirms it:
`std::shared_ptr<Promise<std::shared_ptr<Promise<GateDecision>>>>` (`HybridAudioBrowserSpecSwift.hpp:411`) → Swift `Promise<Promise<GateDecision>>`. Outer `.await()` yields the inner `Promise<GateDecision>`; inner `.await()` yields the decision. This matches the established `resolveLayer` precedent (`BrowserManager.swift:530`, `return try await resolver().await().await()`) exactly — the codegen wraps the non-void native→JS callback return in `Promise`, and the JS side's own `async` adds the second layer. So the shape is right and neither `.await()` is spurious.

The only nit: one `try?` covers **both** awaits, so a rejection at *either* layer is indistinguishable and silent (feeds C1). If C1 is fixed to fail-closed, that's fine. If fail-open is kept, at least `do/catch` and `logger.error` the rejection so a resolver crash isn't invisible in production (today it vanishes — there is no log on the gate path, unlike `resolveAlbumUrl` at `CarPlayNowPlayingManager.swift:242` which logs its catch).

### M2 — `resolveGate` is `@MainActor`-hopped correctly, but the JS callback runs off the call site's assumptions
`ios/HybridAudioBrowser.swift:577-585`

`gateDecision` is `@MainActor`; every call site (`showGatedTabBar`, `navigateToUrl`, the Siri funnel's `Task { @MainActor in }`) is already on the main actor, and `resolveGate(...)` is a Nitro callback property whose invocation marshals to the JS thread internally and resolves the promise back. `await` suspends the main actor at the `.await()` points without blocking it. This is the correct pattern and matches `resolveAlbumUrl`. No torn reads: `isGateActive`/`hasResolver`/`defaultChrome` are all `private(set)` and only mutated inside `onMainActor { }` in `setGate`/`clearGate`, and only read inside the `@MainActor` `gateDecision` — so the compound (active+chrome+hasResolver) read at `:579-580` is atomic w.r.t. the main actor. **No data race.** (One caveat: `setGate`/`clearGate` themselves are the Nitro spec methods called from the JS thread; they wrap their writes in `onMainActor` which does a `DispatchQueue.main.sync` — correct, but note `onMainActor`'s `.sync` from the JS thread while a `gateDecision` await is suspended on the main actor is fine because the await isn't holding the thread.) Recording as Minor/informational: the threading is sound.

### M3 — `clearGate` guards on `isGateActive` but `setGate` does not — a redundant `setGate` re-emits and forces a full CarPlay rebuild
`ios/HybridAudioBrowser.swift:553-559`

`clearGate` early-returns if `!isGateActive` (`:564`), so a double-clear is cheap. `setGate` has no such guard: calling `setGate` again while already active (e.g. an in-place chrome update, the documented "seamless update" use-case) always `emit(true)`, which drives `handleGateChanged(true)` → `popToRootTemplate` + full `showTabBar` rebuild + `setupNowPlayingButtons`. The doc comment on the gate (`:534`) and `gate.ts`'s `setGate` doc both promise "Updating is seamless — the page changes in place, with no navigation reset." But `handleGateChanged` **always** calls `popToRootTemplate(animated: false)` (`:1080`) on any gate change while tabs exist — so an in-place chrome update **does** reset the user's pushed navigation to root. That contradicts the "no navigation reset" promise.

This may be acceptable (you're gated; being bounced to root on a gate *update* is arguably fine since pushed content is gated anyway), but it does **not** match the stated "seamless, no navigation reset" contract. Either fix the comment or, if a pushed gate page should survive a chrome update, special-case "still gated, only chrome changed" to re-render in place without `popToRootTemplate`.

### M4 — `makeGateTemplate` button uses a 1pt transparent placeholder image; fragile but matches existing pattern
`ios/CarPlay/CarPlayController.swift:1112-1117`

`CPButton(image:)` requires an image; the code feeds a 1×1 transparent render and sets `.title` after. This is a known CarPlay workaround and is self-documented. Not a defect, but it depends on CarPlay continuing to render a titled button with a transparent image — brittle against OS changes, no fallback if the title-only render regresses. Acceptable; noting for completeness.

### M5 — Built-in fallback string `"Unavailable"` is hard-coded in two places (native) — acceptable, but verify it's the single source
`ios/HybridAudioBrowser.swift:544`

`static let builtInGate = NativeGate(title: "Unavailable", …)` is the one native source of truth, consumed by both `gateDecision` (`:584`) and `makeGateTemplate`'s `gateChrome ?? HybridAudioBrowser.builtInGate` (`:1106`). Good — single definition, library-generic wording (no product name). The double fallback (`gateDecision` already resolves to `builtInGate`, then `makeGateTemplate` falls back to it *again*) is harmless belt-and-suspenders. No leak of product-specific naming anywhere in the gate code. ✓

### M6 — `gateChangedEmitter` narrowed to `Emitter<Bool>`; no subscriber still needs the chrome
`ios/HybridAudioBrowser.swift:91`, `CarPlayController.swift:305`

The only subscriber is `handleGateChanged(_ active: Bool)` (`CarPlayController.swift:307`), which re-derives chrome per-serve via `gateDecision`. `CarPlayNowPlayingManager` reads `audioBrowser?.isGateActive` directly (`:113`), not the emitter. No subscriber is left expecting the old `NativeBrowseGate?` payload. The narrowing is clean. ✓

### M7 — Now-playing custom-button gating regression-free; transport preserved
`ios/CarPlay/CarPlayNowPlayingManager.swift:113`

`(audioBrowser?.isGateActive ?? false) ? [] : config.carPlayNowPlayingButtons` — custom buttons (favorite/shuffle/repeat/rate) hide while gated, system transport untouched. Matches the old `browseGate == nil ? config… : []` semantics exactly (active ⇒ empty). The `?? false` default (no browser ⇒ not gated ⇒ show buttons) is the right failure mode here (no browser means no gate context). ✓ Note: this reads `isGateActive` **synchronously** (no resolver hop) — correct, because button visibility is a global "is a gate up at all" question, not a per-request one. Good call not to route it through `gateDecision`.

### M8 — Resume branch proven ungated; `params` move is a no-op for resume
`ios/HybridAudioBrowser.swift:1690-1716`

Traced against `feature-fry` base. Both old and new: `if criteria.isResume { … return }` executes **before** any gate check, with an unconditional `return` on all three sub-branches (warm / cold-restore / nothing). The `params` assembly moved from inside the old `do {}` (after the `guard browser.browseGate == nil`) to above the new `gateDecision` call (`:1721`), but it is still **after** the resume `return`, so resume never builds `params` and never touches the gate. The non-resume search path is byte-for-byte equivalent except the gate check changed from `guard browseGate == nil` to `gateDecision(...).gated`. **Resume is unconditionally ungated and reachable.** ✓ The only resume-adjacent risk is C1: if a resolver error fails open, the *search* branch leaks — but resume itself is never gated regardless.

### M9 — `makeGateTemplate` `userInfo` marker renamed `"browseGate"` → `"gate"`, set-only
`ios/CarPlay/CarPlayController.swift:1144`

Renamed for consistency; it's documentary (`getPath` keys on `"path"`, and gate pages are identified by *absence* of a path, not by this marker). Grep confirms nothing reads `"gate"` / `"browseGate"` from `userInfo`. Dead-but-harmless; could be dropped entirely. Minor.

---

## Summary of recommendations (priority order)
1. **C1 (decide + fix):** rule on fail-open vs fail-closed in the design doc; make resolver-error fail **closed** (gate with default/built-in) on both iOS and Android, or explicitly document and opt-in fail-open. Currently a subscription-gate content leak on resolver error.
2. **I2:** serialize gated tab-bar builds with a generation token to prevent stale/duplicate `setRootTemplate` under rapid gate changes / JS reload.
3. **I3:** strengthen the gated in-place `updateTemplates` swap to a per-slot identity check (not count-only); add a regression test + comment for the load-bearing eager `loadContent` loop.
4. **I1 / M3:** align docs with reality — `onGate` fans out per tab (debounce expectation); `setGate`-while-gated *does* reset pushed navigation despite the "seamless, no reset" promise.
5. **M1:** if fail-open is kept, log the swallowed resolver rejection (today it's invisible).

No force-unwraps, data races, or product-name leaks found in the new gate code. Double-`.await().await()` is the correct unwrap for the generated `Promise<Promise<GateDecision>>` type.

---

## iOS fix wave

Fixes applied on `feature-fry-gate` in response to the findings above.

### C1 — fail CLOSED on resolver error (FIXED)
`ios/HybridAudioBrowser.swift` `gateDecision(for:)`. A resolver error (thrown/rejected predicate, or a rejection at either `.await()` layer from bridge tear-down / JS reload / serialization) now **gates** with the stored default / built-in chrome instead of falling through to "allow". A *successful* resolver returning `gated: false` still allows. The `!hasResolver` static path also resolves `defaultChrome ?? builtInGate` (belt-and-suspenders; `defaultChrome` is always set on that path). Doc comment updated to state errors fail closed by design. Design doc `2026-06-23-gate-design.md` gained an explicit "Resolver errors fail CLOSED" paragraph under the implementation note. (Android peer `AudioBrowser.kt:754` still fails open — left for a matched cross-platform change; called out below.)

### I2 — serialize gated tab-bar builds (FIXED)
`ios/CarPlay/CarPlayController.swift`. Added `gateBuildGeneration: UInt` mirroring `albumArtistGeneration`. `showGatedTabBar` bumps it at entry, captures the value, and bails (`guard gateBuildGeneration == generation else { return }`) after each per-tab `gateDecision` await — before any `setRootTemplate` / `updateTemplates`. A newer build (rapid set→clear, in-place chrome update, JS-reload re-seed) now supersedes an in-flight one, preventing the stale/duplicate root-template set.

### I1 / M3 docs (FIXED)
- `src/features/gate.ts`: `GateEvent` JSDoc now states `onGate` fires **once per gated serve**, not once per user action — on car surfaces one action (tab-bar build) fires one event per gated tab; consumer debounces.
- `src/features/gate.ts`: `setGate` JSDoc no longer claims fully "seamless / no navigation reset"; it states that on CarPlay any gate change pops pushed navigation to root.
- `docs/.../2026-06-23-gate-design.md`: `onGate` "per gated serve" line expanded to spell out the per-tab fan-out.

### Deferred (with reason)
- **I1 part 2 — add `path?`/`search?` to `GateEvent`:** deferred. The design explicitly left this as an additive-later seam; adding it now is a product/consumer call, not a low-risk correctness fix. Documented the coarseness instead.
- **I3 — per-slot identity check on the `updateTemplates` swap + regression test:** deferred. The count-only swap is a real weakness, but tightening it to per-slot `url`/gated-ness identity is a behavioural change with its own edge cases (selected-index preservation), not a clearly-correct one-liner. Review itself rates the mis-swap "low-probability". The eager `loadContent` loop it depends on already has a load-bearing comment in place. Worth its own focused change + manual test.
- **M1 — log the swallowed resolver rejection:** moot. C1 now fails closed on rejection, so a resolver crash visibly gates rather than vanishing; an explicit error log on the gate path is a nice-to-have but no longer masks a content leak. Left out to avoid noise on benign reload-time rejections.
- **M3 — actually preserve pushed navigation on a chrome-only update:** deferred (doc corrected instead, per the task). `handleGateChanged` still pops to root on every gate change; special-casing "still gated, chrome only" to re-render in place is a behavioural change beyond this wave's scope.
- **Android fail-open (C1 cross-platform):** deferred to a matched Android change — out of scope for an iOS-only wave; flagged so it isn't lost.

### Verify
`swift test --disable-sandbox` from the worktree root: **431 tests in 80 suites passed** (0 failures; the pre-existing `PlaybackStateMachineTests` failures did not reproduce in this run). Gate code compiles.
